package wireguard

import (
	"strings"
	"testing"
)

func TestParseConfig(t *testing.T) {
	conf := `[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.0.0.1/24
DNS = 1.1.1.1

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
Endpoint = 192.168.1.1:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25`

	config, err := ParseConfig(conf)
	if err != nil {
		t.Fatalf("ParseConfig failed: %v", err)
	}
	if config.Interface.PrivateKey != "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=" {
		t.Errorf("expected private key, got %q", config.Interface.PrivateKey)
	}
	if config.Interface.Address != "10.0.0.1/24" {
		t.Errorf("expected address 10.0.0.1/24, got %q", config.Interface.Address)
	}
	if config.Interface.DNS != "1.1.1.1" {
		t.Errorf("expected DNS 1.1.1.1, got %q", config.Interface.DNS)
	}
	if len(config.Peers) != 1 {
		t.Fatalf("expected 1 peer, got %d", len(config.Peers))
	}
	if config.Peers[0].Endpoint != "192.168.1.1:51820" {
		t.Errorf("expected endpoint, got %q", config.Peers[0].Endpoint)
	}
	if len(config.Peers[0].AllowedIPs) != 2 {
		t.Errorf("expected 2 allowed IPs, got %d", len(config.Peers[0].AllowedIPs))
	}
}

func TestParseConfigMissingPrivateKey(t *testing.T) {
	conf := `[Interface]
Address = 10.0.0.1/24

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
Endpoint = 192.168.1.1:51820`

	_, err := ParseConfig(conf)
	if err == nil {
		t.Fatal("expected error for missing private key")
	}
	if !strings.Contains(err.Error(), "PrivateKey") {
		t.Errorf("expected PrivateKey error, got %v", err)
	}
}

func TestParseConfigNoPeers(t *testing.T) {
	conf := `[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=`

	_, err := ParseConfig(conf)
	if err == nil {
		t.Fatal("expected error for no peers")
	}
	if !strings.Contains(err.Error(), "Peer") {
		t.Errorf("expected Peer error, got %v", err)
	}
}

func TestTunnelState(t *testing.T) {
	tunnel := NewTunnel()
	if tunnel.IsUp() {
		t.Fatal("new tunnel should be down")
	}
	if tunnel.State() != 0 {
		t.Errorf("expected state 0, got %d", tunnel.State())
	}

	conf := &Config{
		Interface: InterfaceConfig{PrivateKey: "test"},
		Peers:     []PeerConfig{{PublicKey: "test", Endpoint: "1.2.3.4:51820"}},
	}
	tunnel.SetConfig(conf)

	if err := tunnel.Up(); err != nil {
		t.Fatalf("Up failed: %v", err)
	}
	if !tunnel.IsUp() {
		t.Fatal("tunnel should be up after Up()")
	}
	if tunnel.State() != 1 {
		t.Errorf("expected state 1, got %d", tunnel.State())
	}

	tunnel.Down()
	if tunnel.IsUp() {
		t.Fatal("tunnel should be down after Down()")
	}
}

func TestTunnelUpNoConfig(t *testing.T) {
	tunnel := NewTunnel()
	err := tunnel.Up()
	if err == nil {
		t.Fatal("expected error for Up without config")
	}
}

func TestTunnelStats(t *testing.T) {
	tunnel := NewTunnel()
	tunnel.AddRxBytes(1000)
	tunnel.AddTxBytes(500)
	tunnel.AddRxBytes(2000)

	if tunnel.RxBytes() != 3000 {
		t.Errorf("expected 3000 rx bytes, got %d", tunnel.RxBytes())
	}
	if tunnel.TxBytes() != 500 {
		t.Errorf("expected 500 tx bytes, got %d", tunnel.TxBytes())
	}

	tunnel.ResetStats()
	if tunnel.RxBytes() != 0 || tunnel.TxBytes() != 0 {
		t.Error("stats not reset")
	}
}

func TestValidateKey(t *testing.T) {
	// Valid 32-byte base64 key
	validKey := "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
	if !ValidateKey(validKey) {
		t.Error("valid key rejected")
	}

	// Invalid key
	if ValidateKey("invalid") {
		t.Error("invalid key accepted")
	}
	if ValidateKey("") {
		t.Error("empty key accepted")
	}
}

func TestConfigSummary(t *testing.T) {
	config := &Config{
		Interface: InterfaceConfig{
			Address: "10.0.0.1/24",
			DNS:     "1.1.1.1",
		},
		Peers: []PeerConfig{
			{Endpoint: "192.168.1.1:51820"},
		},
	}
	summary := config.Summary()
	if !strings.Contains(summary, "10.0.0.1/24") {
		t.Errorf("summary missing address: %s", summary)
	}
	if !strings.Contains(summary, "1.1.1.1") {
		t.Errorf("summary missing DNS: %s", summary)
	}
	if !strings.Contains(summary, "192.168.1.1:51820") {
		t.Errorf("summary missing endpoint: %s", summary)
	}
}
