package golib

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func TestDirectDNS_BootstrapRaceSkipsStalledAddress(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	stalled := make(chan struct{})
	stopped := make(chan struct{})
	winner, peer := net.Pipe()
	defer peer.Close()
	defer winner.Close()
	conn, err := raceDirectDNSAddresses(ctx, []string{"192.0.2.1", "192.0.2.2"}, "853",
		func(ctx context.Context, address string) (net.Conn, error) {
			if address == "192.0.2.1:853" {
				close(stalled)
				<-ctx.Done()
				close(stopped)
				return nil, ctx.Err()
			}
			select {
			case <-stalled:
				return winner, nil
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		})
	if err != nil || conn != winner {
		t.Fatalf("reachable second address must win within the query budget: conn=%v err=%v", conn, err)
	}
	select {
	case <-stopped:
	case <-ctx.Done():
		t.Fatal("losing dial was not cancelled")
	}
}

func TestDirectDNS_BootstrapRaceClosesLateConnection(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	winner, winnerPeer := net.Pipe()
	defer winner.Close()
	defer winnerPeer.Close()
	loser, loserPeer := net.Pipe()
	defer loser.Close()
	defer loserPeer.Close()
	conn, err := raceDirectDNSAddresses(ctx, []string{"192.0.2.1", "192.0.2.2"}, "443",
		func(ctx context.Context, address string) (net.Conn, error) {
			if address == "192.0.2.1:443" {
				return winner, nil
			}
			<-ctx.Done()
			return loser, nil // A connection completed concurrently with cancellation.
		})
	if err != nil || conn != winner {
		t.Fatalf("winner: conn=%v err=%v", conn, err)
	}
	_ = loserPeer.SetReadDeadline(time.Now().Add(time.Second))
	_, err = loserPeer.Read(make([]byte, 1))
	if err == nil || isTimeoutClass(err) {
		t.Fatalf("losing connection must close, got %v", err)
	}
}

func TestDirectDNS_BootstrapRaceReturnsFailureAndCancellation(t *testing.T) {
	failure := errors.New("fixture dial failure")
	_, err := raceDirectDNSAddresses(context.Background(), []string{"192.0.2.1", "192.0.2.2"}, "853",
		func(context.Context, string) (net.Conn, error) { return nil, failure })
	if !errors.Is(err, failure) {
		t.Fatalf("all-address failure: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = raceDirectDNSAddresses(ctx, []string{"192.0.2.1"}, "853",
		func(ctx context.Context, _ string) (net.Conn, error) {
			<-ctx.Done()
			return nil, ctx.Err()
		})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled dial: %v", err)
	}
}

func TestDirectDNS_BootstrapHostnameCaseIsIgnored(t *testing.T) {
	for _, raw := range []string{
		`{"transport":"tls","servers":["DNS.Test:853"],"bootstrap":{"Dns.Test":["127.0.0.1"]}}`,
		`{"transport":"https","servers":["https://DNS.Test/DNS-query"],"bootstrap":{"Dns.Test":["127.0.0.1"]}}`,
	} {
		cfg, err := parseDirectDNSConfig(raw)
		if err != nil {
			t.Fatal(err)
		}
		if len(cfg.Bootstrap["dns.test"]) != 1 {
			t.Fatalf("bootstrap keys were not normalized: %v", cfg.Bootstrap)
		}
	}
}
