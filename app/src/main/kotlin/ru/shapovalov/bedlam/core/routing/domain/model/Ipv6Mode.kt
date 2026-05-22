package ru.shapovalov.bedlam.core.routing.domain.model

enum class Ipv6Mode {
    /** Claim the v6 default route; v6 traffic flows through the tunnel. */
    Enabled,

    /** Don't claim any v6 routes; v6 traffic goes via the underlying network. */
    Disabled,

    /** v6 traffic is never tunneled; reserved for future v6-specific behavior. */
    BypassOnly,
}
