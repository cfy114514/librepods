#pragma once

// API 37 stacks must keep their own negotiation result unless a user opts into
// the legacy compatibility workaround for a ROM that still needs it.
constexpr bool use_l2cap_workaround(int api_level, bool force_legacy) {
    return force_legacy || api_level < 37;
}

static_assert(use_l2cap_workaround(33, false));
static_assert(use_l2cap_workaround(36, false));
static_assert(!use_l2cap_workaround(37, false));
static_assert(!use_l2cap_workaround(38, false));
static_assert(use_l2cap_workaround(37, true));
static_assert(use_l2cap_workaround(38, true));
