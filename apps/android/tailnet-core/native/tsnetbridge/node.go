package tsnetbridge

import (
	"bytes"
	"context"
	"crypto/tls"
	"net"
	"strings"
	"sync"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"
)

const startTimeout = 60 * time.Second

type enrollment struct {
	Hostname       string
	ControlURL     string
	MagicDNS       string
	PinnedIPv4     string
	PinnedIPv6     string
	AppKeyFP       string
	TicketDigest   string
	PolicyDigest   string
	DeviceID       string
	Generation     uint64
	PolicyRevision uint64
}

// nodeDial is the internal pinned dialer. It is a package variable only so
// unit tests can substitute an inert peer without touching the exported API.
var nodeDial = func(ctx context.Context, s *tsnet.Server, address string) (net.Conn, error) {
	return s.Dial(ctx, "tcp", address)
}

// nodeStatus is the internal backend-status seam used to classify DIRECT,
// RELAY, and OFFLINE from the real Tailscale peer state.
var nodeStatus = func(ctx context.Context, s *tsnet.Server) (*ipnstate.Status, error) {
	return s.Up(ctx)
}

// Node is the opaque userspace node handle returned by Start. Kotlin never
// constructs it and never supplies hostname/auth-key/control-URL parameters.
type Node struct {
	mu          sync.Mutex
	server      *tsnet.Server
	store       *memoryStateStore
	enrollment  *enrollment
	channel     *Channel
	channelPath string
	closed      bool
}

// Start boots the pinned tsnet userspace node from a closed enrollment bundle
// and the app-private restored state blob. The auth key is consumed and wiped
// during Start; it is never returned, logged, or persisted.
func Start(bootstrapBytes, restoredStateBytes []byte, sink StateSink) (*Node, error) {
	if sink == nil {
		return nil, newBridgeError(ErrCodeInvalidBundle, "state sink is required")
	}
	bundle, err := decodeBundle(bootstrapBytes)
	if err != nil {
		return nil, err
	}
	defer wipe(bundle.authKey)

	store := newMemoryStateStore(sink)
	if len(restoredStateBytes) > 0 {
		if err := store.RestoreFrom(restoredStateBytes); err != nil {
			return nil, err
		}
		if bundle.warmStart && len(bundle.authKey) != 0 {
			return nil, newBridgeError(ErrCodeInvalidBundle, "warm start must not carry an auth key")
		}
	} else if bundle.warmStart {
		return nil, newBridgeError(ErrCodeInvalidBundle, "warm start requires restored state")
	}

	authKey := bytes.Clone(bundle.authKey)
	defer wipe(authKey)
	enroll := &enrollment{
		Hostname:       bundle.hostname,
		ControlURL:     bundle.controlURL,
		MagicDNS:       bundle.magicDNS,
		PinnedIPv4:     bundle.pinnedIPv4,
		PinnedIPv6:     bundle.pinnedIPv6,
		AppKeyFP:       bundle.appKeyFP,
		TicketDigest:   bundle.ticketDigest,
		PolicyDigest:   bundle.policyDigest,
		DeviceID:       bundle.deviceID,
		Generation:     bundle.generation,
		PolicyRevision: bundle.policyRevision,
	}

	envknob.SetNoLogsNoSupport()
	server := &tsnet.Server{
		Hostname:     enroll.Hostname,
		ControlURL:   enroll.ControlURL,
		AuthKey:      string(authKey),
		Store:        store,
		Ephemeral:    false,
		RunWebClient: false,
		Logf:         codeOnlyLogf,
		UserLogf:     codeOnlyLogf,
	}
	node := &Node{server: server, store: store, enrollment: enroll}

	ctx, cancel := context.WithTimeout(context.Background(), startTimeout)
	defer cancel()
	if err := server.Start(); err != nil {
		store.Close()
		return nil, newBridgeError(ErrCodeControlUnreachable, "Tailnet control unreachable")
	}
	if _, err := server.Up(ctx); err != nil {
		_ = server.Close()
		store.Close()
		return nil, newBridgeError(ErrCodeControlUnreachable, "Tailnet control unreachable")
	}
	// The auth key is no longer reachable through the server; clear both the
	// caller-owned, decoded copy and the wrapper copy.
	server.AuthKey = ""
	wipe(bundle.authKey)
	wipe(authKey)

	if !bundle.warmStart {
		waitCtx, waitCancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer waitCancel()
		if !store.waitFirstDurable(waitCtx) {
			_ = server.Close()
			store.Close()
			return nil, newBridgeError(ErrCodeStatePersistFailed, "node state persistence failed")
		}
	}
	return node, nil
}

// OpenPairedBridge validates the ALBIND1 binding against the enrollment and
// opens the single pinned WSS control channel.
func (n *Node) OpenPairedBridge(bindingBytes []byte) (*Channel, error) {
	return n.openChannel(bindingBytes)
}

// Path reports the backend-derived DIRECT/RELAY/OFFLINE path for the pinned
// Bridge peer. It never infers a path from request success or RTT.
func (n *Node) Path(bindingBytes []byte) (string, error) {
	binding, err := decodeVerifiedBinding(bindingBytes)
	if err != nil {
		return "", err
	}
	if err := n.matchBinding(binding); err != nil {
		return "", err
	}
	n.mu.Lock()
	server := n.server
	enroll := n.enrollment
	ch := n.channel
	n.mu.Unlock()
	if server == nil || enroll == nil || ch == nil {
		return "OFFLINE", nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	status, err := nodeStatus(ctx, server)
	if err != nil {
		// A real backend error is not a path; keep the channel closed-fail
		// semantics consistent by reporting OFFLINE.
		return "OFFLINE", nil
	}
	for _, peer := range status.Peer {
		if !peerHas(peer, enroll) {
			continue
		}
		if peer.CurAddr != "" {
			return "DIRECT", nil
		}
		if peer.Relay != "" {
			return "RELAY", nil
		}
		return "OFFLINE", nil
	}
	return "OFFLINE", nil
}

// Stop fences the active channel, closes the server once, and zeroizes the
// in-memory state store. It is idempotent.
func (n *Node) Stop() error {
	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		return nil
	}
	n.closed = true
	ch := n.channel
	n.channel = nil
	server := n.server
	store := n.store
	n.server = nil
	n.store = nil
	n.enrollment = nil
	n.mu.Unlock()

	if ch != nil {
		_ = ch.Close()
	}
	if server != nil {
		if err := server.Close(); err != nil {
			return newBridgeError(ErrCodeChannelClosed, "node close failed")
		}
	}
	if store != nil {
		store.Close()
	}
	return nil
}

func peerHas(peer *ipnstate.PeerStatus, enroll *enrollment) bool {
	if peer == nil {
		return false
	}
	for _, ip := range peer.TailscaleIPs {
		if (enroll.PinnedIPv4 != "" && ip.String() == enroll.PinnedIPv4) ||
			(enroll.PinnedIPv6 != "" && ip.String() == enroll.PinnedIPv6) {
			return true
		}
	}
	return false
}

func codeOnlyLogf(string, ...any) {
	// Deliberately emits nothing: tsnet/upstream arguments may contain
	// hostnames, peer addresses, or enrollment URLs that must not leave Go.
}

func pinnedTLSConfig(serverName string) *tls.Config {
	return &tls.Config{
		ServerName: serverName,
		MinVersion: tls.VersionTLS12,
	}
}

var _ = strings.TrimSpace
