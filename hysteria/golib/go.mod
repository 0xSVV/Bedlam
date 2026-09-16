module bedlam/golib

go 1.26.0

toolchain go1.26.8

require (
	github.com/apernet/hysteria/core/v2 v2.0.0-00010101000000-000000000000
	github.com/apernet/hysteria/extras/v2 v2.0.0-00010101000000-000000000000
	github.com/apernet/quic-go v0.62.1-0.20260912175848-73339f7edbb9
	github.com/refraction-networking/utls v1.8.2
	github.com/sagernet/sing v0.3.8
	github.com/sagernet/sing-tun v0.3.2
	golang.org/x/mobile v0.0.0-20260908204917-8b95e45f8d3e
	golang.org/x/sync v0.23.0
)

require (
	github.com/andybalholm/brotli v1.1.0 // indirect
	github.com/fsnotify/fsnotify v1.7.0 // indirect
	github.com/go-ole/go-ole v1.3.0 // indirect
	github.com/google/btree v1.1.2 // indirect
	github.com/google/gopacket v1.1.19 // indirect
	github.com/huin/goupnp v1.2.0 // indirect
	github.com/jackpal/go-nat-pmp v1.0.2 // indirect
	github.com/klauspost/compress v1.17.9 // indirect
	github.com/koron/go-ssdp v0.0.4 // indirect
	github.com/libp2p/go-nat v1.0.1-0.20250821073202-01afc089f138 // indirect
	github.com/libp2p/go-netroute v0.2.1 // indirect
	github.com/pion/dtls/v3 v3.1.4 // indirect
	github.com/pion/logging v0.2.4 // indirect
	github.com/pion/stun/v3 v3.1.6 // indirect
	github.com/pion/transport/v4 v4.0.2 // indirect
	github.com/quic-go/qpack v0.6.0 // indirect
	github.com/sagernet/gvisor v0.0.0-20240428053021-e691de28565f // indirect
	github.com/sagernet/netlink v0.0.0-20240523065131-45e60152f9ba // indirect
	github.com/stretchr/objx v0.5.3 // indirect
	github.com/stretchr/testify v1.12.1 // indirect
	github.com/vishvananda/netns v0.0.0-20211101163701-50045581ed74 // indirect
	github.com/wlynxg/anet v0.0.5 // indirect
	go.yaml.in/yaml/v3 v3.0.5 // indirect
	go4.org/netipx v0.0.0-20231129151722-fdeea329fbba // indirect
	golang.org/x/crypto v0.57.0 // indirect
	golang.org/x/exp v0.0.0-20240506185415-9bf2ced13842 // indirect
	golang.org/x/mod v0.41.0 // indirect
	golang.org/x/net v0.59.0 // indirect
	golang.org/x/sys v0.48.0 // indirect
	golang.org/x/text v0.42.0 // indirect
	golang.org/x/time v0.15.0 // indirect
	golang.org/x/tools v0.50.0 // indirect
)

replace github.com/apernet/hysteria/core/v2 => ../upstream/core

replace github.com/apernet/hysteria/extras/v2 => ../upstream/extras
