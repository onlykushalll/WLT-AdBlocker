// Package wireguard provides a lightweight WireGuard tunnel interface for WLT.
//
// Rather than pulling in the full wireguard-go library (which is large and
// has complex dependencies), this package provides a configuration parser
// and tunnel manager that can route DNS queries through an encrypted tunnel.
//
// The actual WireGuard userspace implementation would use wireguard-go,
// but this package handles:
//   - Parsing WireGuard .conf files
//   - Managing tunnel state (up/down)
//   - Tracking data usage
//   - Providing config to the VPN service for routing
//
// Phase 11a: gomobile-compatible WireGuard config + state management
package wireguard

import (
        "encoding/base64"
        "fmt"
        "strings"
        "sync"
)

// Config represents a parsed WireGuard configuration.
type Config struct {
        Interface   InterfaceConfig
        Peers       []PeerConfig
}

// InterfaceConfig is the [Interface] section of a WireGuard config.
type InterfaceConfig struct {
        PrivateKey string
        Address    string
        DNS        string
        MTU        string
}

// PeerConfig is a [Peer] section of a WireGuard config.
type PeerConfig struct {
        PublicKey    string
        PresharedKey string
        Endpoint     string
        AllowedIPs   []string
        Keepalive    string
}

// Tunnel manages a WireGuard tunnel's state.
type Tunnel struct {
        mu       sync.RWMutex
        config   *Config
        state    TunnelState
        rxBytes  uint64
        txBytes  uint64
}

// TunnelState represents the current state of the tunnel.
type TunnelState int

const (
        StateDown TunnelState = 0
        StateUp   TunnelState = 1
)

// NewTunnel creates a new Tunnel with the given config.
func NewTunnel() *Tunnel {
        return &Tunnel{
                state: StateDown,
        }
}

// ParseConfig parses a WireGuard .conf file content into a Config.
// Supports standard INI format with [Interface] and [Peer] sections.
func ParseConfig(conf string) (*Config, error) {
        config := &Config{}
        var currentPeer *PeerConfig

        for _, line := range strings.Split(conf, "\n") {
                line = strings.TrimSpace(line)
                if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
                        continue
                }

                if strings.HasPrefix(line, "[Interface]") {
                        continue
                }
                if strings.HasPrefix(line, "[Peer]") {
                        config.Peers = append(config.Peers, PeerConfig{})
                        currentPeer = &config.Peers[len(config.Peers)-1]
                        continue
                }

                // Parse key=value
                idx := strings.Index(line, "=")
                if idx < 0 {
                        continue
                }
                key := strings.TrimSpace(line[:idx])
                value := strings.TrimSpace(line[idx+1:])

                if currentPeer == nil {
                        // Interface section
                        switch key {
                        case "PrivateKey":
                                config.Interface.PrivateKey = value
                        case "Address":
                                config.Interface.Address = value
                        case "DNS":
                                config.Interface.DNS = value
                        case "MTU":
                                config.Interface.MTU = value
                        }
                } else {
                        // Peer section
                        switch key {
                        case "PublicKey":
                                currentPeer.PublicKey = value
                        case "PresharedKey":
                                currentPeer.PresharedKey = value
                        case "Endpoint":
                                currentPeer.Endpoint = value
                        case "AllowedIPs":
                                currentPeer.AllowedIPs = strings.Split(value, ",")
                                for i, ip := range currentPeer.AllowedIPs {
                                        currentPeer.AllowedIPs[i] = strings.TrimSpace(ip)
                                }
                        case "PersistentKeepalive":
                                currentPeer.Keepalive = value
                        }
                }
        }

        if config.Interface.PrivateKey == "" {
                return nil, fmt.Errorf("wireguard: missing PrivateKey in Interface section")
        }
        if len(config.Peers) == 0 {
                return nil, fmt.Errorf("wireguard: no Peer sections found")
        }
        if config.Peers[0].PublicKey == "" {
                return nil, fmt.Errorf("wireguard: missing PublicKey in Peer section")
        }
        if config.Peers[0].Endpoint == "" {
                return nil, fmt.Errorf("wireguard: missing Endpoint in Peer section")
        }

        return config, nil
}

// SetConfig sets the tunnel configuration.
func (t *Tunnel) SetConfig(config *Config) {
        t.mu.Lock()
        defer t.mu.Unlock()
        t.config = config
}

// GetConfig returns the current tunnel configuration.
func (t *Tunnel) GetConfig() *Config {
        t.mu.RLock()
        defer t.mu.RUnlock()
        return t.config
}

// Up brings the tunnel up. Returns an error if no config is set.
func (t *Tunnel) Up() error {
        t.mu.Lock()
        defer t.mu.Unlock()
        if t.config == nil {
                return fmt.Errorf("wireguard: no config set")
        }
        t.state = StateUp
        return nil
}

// Down brings the tunnel down.
func (t *Tunnel) Down() {
        t.mu.Lock()
        defer t.mu.Unlock()
        t.state = StateDown
}

// IsUp returns true if the tunnel is currently up.
func (t *Tunnel) IsUp() bool {
        t.mu.RLock()
        defer t.mu.RUnlock()
        return t.state == StateUp
}

// State returns the current tunnel state (0=down, 1=up).
func (t *Tunnel) State() int {
        t.mu.RLock()
        defer t.mu.RUnlock()
        return int(t.state)
}

// RxBytes returns the total bytes received through the tunnel.
func (t *Tunnel) RxBytes() uint64 {
        t.mu.RLock()
        defer t.mu.RUnlock()
        return t.rxBytes
}

// TxBytes returns the total bytes sent through the tunnel.
func (t *Tunnel) TxBytes() uint64 {
        t.mu.RLock()
        defer t.mu.RUnlock()
        return t.txBytes
}

// AddRxBytes adds to the received bytes counter.
func (t *Tunnel) AddRxBytes(n uint64) {
        t.mu.Lock()
        t.rxBytes += n
        t.mu.Unlock()
}

// AddTxBytes adds to the sent bytes counter.
func (t *Tunnel) AddTxBytes(n uint64) {
        t.mu.Lock()
        t.txBytes += n
        t.mu.Unlock()
}

// ResetStats resets the byte counters.
func (t *Tunnel) ResetStats() {
        t.mu.Lock()
        t.rxBytes = 0
        t.txBytes = 0
        t.mu.Unlock()
}

// ValidateKey checks if a base64-encoded key is valid (32 bytes).
func ValidateKey(key string) bool {
        data, err := base64.StdEncoding.DecodeString(key)
        if err != nil {
                return false
        }
        return len(data) == 32
}

// ConfigSummary returns a human-readable summary of the config.
func (c *Config) Summary() string {
        if c == nil {
                return "no config"
        }
        parts := []string{}
        if c.Interface.Address != "" {
                parts = append(parts, "addr: "+c.Interface.Address)
        }
        if c.Interface.DNS != "" {
                parts = append(parts, "dns: "+c.Interface.DNS)
        }
        if len(c.Peers) > 0 {
                parts = append(parts, "endpoint: "+c.Peers[0].Endpoint)
                parts = append(parts, fmt.Sprintf("%d peer(s)", len(c.Peers)))
        }
        return strings.Join(parts, ", ")
}
