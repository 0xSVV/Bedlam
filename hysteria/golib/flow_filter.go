package golib

import M "github.com/sagernet/sing/common/metadata"

// TCPFlowFilter authenticates the owner of a captured flow on Android.
// It receives the original tuple, before DNS rewriting or upstream dialing.
type TCPFlowFilter interface {
	Allow(sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) bool
}

type tcpFlowFilterHolder struct{ filter TCPFlowFilter }

func (s *Session) SetTCPFlowFilter(filter TCPFlowFilter) {
	if filter == nil {
		s.tcpFlowFilter.Store(nil)
	} else {
		s.tcpFlowFilter.Store(&tcpFlowFilterHolder{filter: filter})
	}
}

func (s *Session) allowTCPFlow(source, destination M.Socksaddr) bool {
	holder := s.tcpFlowFilter.Load()
	return holder == nil || holder.filter.Allow(source.Addr.String(), int32(source.Port), destination.Addr.String(), int32(destination.Port))
}
