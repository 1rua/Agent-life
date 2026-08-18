// Command agent-life-tsnet is the private Tailnet ingress sidecar. It never
// opens a public host socket; every accepted connection is authenticated with
// the embedded tsnet LocalClient before it is proxied over a Unix socket.
package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"tailscale.com/client/tailscale/apitype"
	"tailscale.com/tsnet"
)

const (
	fingerprintHeader = "X-Agent-Life-Peer-Fingerprint"
	authKeyMaxBytes   = 16 * 1024
)

func peerFingerprint(nodeKey string) string {
	sum := sha256.Sum256([]byte(nodeKey))
	return "sha256:" + hex.EncodeToString(sum[:])
}

func authenticatedProxy(runtimeSocket string) *httputil.ReverseProxy {
	transport := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			dialer := net.Dialer{Timeout: 5 * time.Second}
			return dialer.DialContext(ctx, "unix", runtimeSocket)
		},
		MaxIdleConns:          64,
		MaxIdleConnsPerHost:   64,
		IdleConnTimeout:       60 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
	}
	return &httputil.ReverseProxy{
		Transport: transport,
		Rewrite: func(request *httputil.ProxyRequest) {
			outbound := request.Out
			outbound.URL.Scheme = "http"
			outbound.URL.Host = "agent-life-bridge-runtime"
			outbound.Host = request.In.Host
			outbound.Header.Del("X-Forwarded-For")
			outbound.Header.Del("X-Forwarded-Host")
			outbound.Header.Del("X-Forwarded-Proto")
		},
		ErrorHandler: func(writer http.ResponseWriter, _ *http.Request, err error) {
			slog.Error("runtime proxy failed", "error", err.Error())
			writer.Header().Set("content-type", "application/json")
			writer.Header().Set("cache-control", "no-store")
			writer.WriteHeader(http.StatusBadGateway)
			_, _ = writer.Write([]byte(`{"error":"BRIDGE_RUNTIME_UNAVAILABLE"}`))
		},
	}
}

type whoIsClient interface {
	WhoIs(context.Context, string) (*apitype.WhoIsResponse, error)
}

type ingressServer struct {
	local       whoIsClient
	proxy       http.Handler
	fingerprint func(nodeKey string) string
}

func (server *ingressServer) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	who, err := server.local.WhoIs(request.Context(), request.RemoteAddr)
	if err != nil || who == nil || who.Node == nil {
		writer.Header().Set("content-type", "application/json")
		writer.Header().Set("cache-control", "no-store")
		writer.WriteHeader(http.StatusUnauthorized)
		_, _ = writer.Write([]byte(`{"error":"TAILNET_PEER_UNAUTHORIZED"}`))
		return
	}
	request.Header.Set(fingerprintHeader, server.fingerprint(who.Node.Key.String()))
	server.proxy.ServeHTTP(writer, request)
}

type config struct {
	stateDir string
	socket   string
	hostname string
	control  string
	authKey  string
	port     uint16
}

func readConfig() (config, error) {
	socket := os.Getenv("AGENT_LIFE_RUNTIME_SOCKET")
	stateDir := os.Getenv("AGENT_LIFE_TSNET_STATE_DIR")
	hostname := os.Getenv("AGENT_LIFE_TSNET_HOSTNAME")
	control := os.Getenv("AGENT_LIFE_TSNET_CONTROL_URL")
	portText := os.Getenv("AGENT_LIFE_TSNET_PORT")
	if socket == "" || stateDir == "" {
		return config{}, errors.New("runtime socket and tsnet state directory are required")
	}
	if !filepath.IsAbs(socket) || !filepath.IsAbs(stateDir) {
		return config{}, errors.New("runtime socket and tsnet state paths must be absolute")
	}
	if hostname == "" {
		hostname = "agent-life-bridge"
	}
	if control == "" {
		control = "https://controlplane.tailscale.com"
	}
	controlURL, err := url.Parse(control)
	if err != nil {
		return config{}, errors.New("tsnet control URL must be an HTTPS authority")
	}
	if controlURL.Scheme != "https" || controlURL.Host == "" || controlURL.User != nil || controlURL.Fragment != "" {
		return config{}, errors.New("tsnet control URL must be an HTTPS authority")
	}
	port := uint16(443)
	if portText != "" {
		value, err := strconv.ParseUint(portText, 10, 16)
		if err != nil || value == 0 {
			return config{}, errors.New("tsnet port must be a non-zero uint16")
		}
		port = uint16(value)
	}
	// Auth key is optional. When absent, tsnet prints an interactive login
	// URL through UserLogf and waits for the operator to authorize the node.
	authKeyPath := os.Getenv("AGENT_LIFE_TSNET_AUTHKEY_FILE")
	var authKey string
	if authKeyPath != "" {
		var authErr error
		authKey, authErr = readAuthKey(authKeyPath)
		if authErr != nil {
			return config{}, authErr
		}
	}
	return config{stateDir, socket, hostname, control, authKey, port}, nil
}

func readAuthKey(path string) (string, error) {
	if path == "" {
		return "", nil
	}
	info, err := os.Lstat(path)
	if err != nil {
		return "", err
	}
	if !info.Mode().IsRegular() || info.Mode().Perm()&0o020 != 0 || info.Mode().Perm()&0o002 != 0 {
		return "", errors.New("tsnet auth key credential must be a non-symlink private file")
	}
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, authKeyMaxBytes+1))
	if err != nil {
		return "", err
	}
	if len(data) == 0 || len(data) > authKeyMaxBytes || strings.ContainsAny(string(data), "\r\n\x00") {
		return "", errors.New("tsnet auth key credential is invalid")
	}
	return strings.TrimSpace(string(data)), nil
}

func run() error {
	cfg, err := readConfig()
	if err != nil {
		return err
	}
	logger := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	server := &tsnet.Server{
		Dir:        cfg.stateDir,
		Hostname:   cfg.hostname,
		ControlURL: cfg.control,
		AuthKey:    cfg.authKey,
		Logf:       func(string, ...any) {},
		UserLogf:   logger.Info,
	}
	defer server.Close()
	// Interactive login has no deadline: without an auth key, tsnet prints the
	// official login URL and waits for the operator to authorize the node.
	// A container stop/signal terminates this process as expected.
	if cfg.authKey == "" {
		logger.Info("no tsnet auth key; waiting for interactive login URL")
	}
	if _, err := server.Up(context.Background()); err != nil {
		return fmt.Errorf("tsnet startup failed: %w", err)
	}
	local, err := server.LocalClient()
	if err != nil {
		return fmt.Errorf("tsnet local client unavailable: %w", err)
	}
	listener, err := server.ListenTLS("tcp", net.JoinHostPort("", strconv.Itoa(int(cfg.port))))
	if err != nil {
		return fmt.Errorf("private tsnet listener unavailable: %w", err)
	}
	defer listener.Close()
	proxy := authenticatedProxy(cfg.socket)
	ingress := &ingressServer{local: local, proxy: proxy, fingerprint: peerFingerprint}
	httpServer := &http.Server{
		Handler:           ingress,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	serveError := make(chan error, 1)
	go func() { serveError <- httpServer.Serve(listener) }()
	logger.Info("private ingress ready", "socket", cfg.socket, "port", cfg.port)
	select {
	case err := <-serveError:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case received := <-signals:
		logger.Info("shutting down", "signal", received.String())
		shutdownContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return httpServer.Shutdown(shutdownContext)
	}
	return nil
}

func main() {
	if err := run(); err != nil {
		slog.Error("ingress failed", "error", err.Error())
		os.Exit(1)
	}
}
