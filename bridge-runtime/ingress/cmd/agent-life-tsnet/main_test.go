package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"tailscale.com/client/tailscale/apitype"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func TestPeerFingerprint(t *testing.T) {
	sum := sha256.Sum256([]byte("nodekey:test"))
	want := "sha256:" + hex.EncodeToString(sum[:])
	if got := peerFingerprint("nodekey:test"); got != want {
		t.Fatalf("peerFingerprint() = %q, want %q", got, want)
	}
}

type fakeWhoIs struct {
	response *apitype.WhoIsResponse
	err      error
}

func (fake fakeWhoIs) WhoIs(context.Context, string) (*apitype.WhoIsResponse, error) {
	return fake.response, fake.err
}

type recordingHandler struct {
	fingerprint string
}

func (handler *recordingHandler) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	handler.fingerprint = request.Header.Get(fingerprintHeader)
	writer.WriteHeader(http.StatusNoContent)
}

func TestIngressRejectsUnauthenticatedPeer(t *testing.T) {
	server := &ingressServer{
		local: fakeWhoIs{err: errors.New("unauthorized")},
		proxy: &recordingHandler{},
	}
	recorder := httptest.NewRecorder()
	server.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "https://bridge/v1/control", nil))
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusUnauthorized)
	}
}

func TestIngressOverwritesClientFingerprintAfterWhoIs(t *testing.T) {
	nodeKey := key.NewNode().Public()
	handler := &recordingHandler{}
	server := &ingressServer{
		local:       fakeWhoIs{response: &apitype.WhoIsResponse{Node: &tailcfg.Node{Key: nodeKey}}},
		proxy:       handler,
		fingerprint: peerFingerprint,
	}
	request := httptest.NewRequest(http.MethodGet, "https://bridge/v1/control", nil)
	request.Header.Set(fingerprintHeader, "sha256:client-forged")
	recorder := httptest.NewRecorder()
	server.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusNoContent)
	}
	if handler.fingerprint != peerFingerprint(nodeKey.String()) {
		t.Fatalf("fingerprint = %q, want %q", handler.fingerprint, peerFingerprint(nodeKey.String()))
	}
}

func TestProxyRewriteRemovesForwardingHeaders(t *testing.T) {
	proxy := authenticatedProxy("/run/agent-life/runtime.sock")
	inbound := httptest.NewRequest(http.MethodGet, "https://bridge/v1/control", nil)
	inbound.Header.Set("X-Forwarded-For", "forged")
	inbound.Header.Set("X-Forwarded-Host", "forged")
	inbound.Header.Set("X-Forwarded-Proto", "forged")
	outbound := inbound.Clone(context.Background())
	proxy.Rewrite(&httputil.ProxyRequest{In: inbound, Out: outbound})
	for _, name := range []string{"X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto"} {
		if got := outbound.Header.Get(name); got != "" {
			t.Fatalf("%s = %q, want empty", name, got)
		}
	}
	if outbound.URL.Scheme != "http" || outbound.URL.Host != "agent-life-bridge-runtime" {
		t.Fatalf("outbound URL = %s, want http://agent-life-bridge-runtime", outbound.URL.String())
	}
}

func TestReadConfigDefaults(t *testing.T) {
	t.Setenv("AGENT_LIFE_RUNTIME_SOCKET", "/run/agent-life/runtime.sock")
	t.Setenv("AGENT_LIFE_TSNET_STATE_DIR", "/var/lib/agent-life-ingress")
	t.Setenv("AGENT_LIFE_TSNET_HOSTNAME", "")
	t.Setenv("AGENT_LIFE_TSNET_CONTROL_URL", "")
	t.Setenv("AGENT_LIFE_TSNET_PORT", "")
	t.Setenv("AGENT_LIFE_TSNET_AUTHKEY_FILE", "")
	cfg, err := readConfig()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.hostname != "agent-life-bridge" {
		t.Fatalf("hostname = %q", cfg.hostname)
	}
	if cfg.control != "https://controlplane.tailscale.com" {
		t.Fatalf("control = %q", cfg.control)
	}
	if cfg.port != 443 || cfg.authKey != "" {
		t.Fatalf("cfg = %+v", cfg)
	}
}

func TestReadConfigRequiresHTTPSAndSafeCredential(t *testing.T) {
	t.Setenv("AGENT_LIFE_RUNTIME_SOCKET", "/run/agent-life/runtime.sock")
	t.Setenv("AGENT_LIFE_TSNET_STATE_DIR", "/var/lib/agent-life-ingress")
	t.Setenv("AGENT_LIFE_TSNET_HOSTNAME", "agent-life-bridge")
	t.Setenv("AGENT_LIFE_TSNET_PORT", "443")
	t.Setenv("AGENT_LIFE_TSNET_CONTROL_URL", "http://control.invalid")
	path := filepath.Join(t.TempDir(), "auth-key")
	if err := os.WriteFile(path, []byte("key"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("AGENT_LIFE_TSNET_AUTHKEY_FILE", path)
	if _, err := readConfig(); err == nil || !strings.Contains(err.Error(), "HTTPS") {
		t.Fatalf("readConfig() error = %v, want HTTPS rejection", err)
	}
	t.Setenv("AGENT_LIFE_TSNET_CONTROL_URL", "https://control.invalid")
	if config, err := readConfig(); err != nil || config.port != 443 || config.authKey != "key" {
		t.Fatalf("readConfig() = %+v, %v", config, err)
	}
}

func TestReadAuthKeyRejectsUnsafeFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "auth-key")
	if err := os.WriteFile(path, []byte("key"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(path, 0o620); err != nil {
		t.Fatal(err)
	}
	if _, err := readAuthKey(path); err == nil {
		t.Fatal("readAuthKey() accepted a group-writable credential")
	}
}
