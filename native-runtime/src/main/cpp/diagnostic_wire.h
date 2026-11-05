#ifndef EASYLSP_DIAGNOSTIC_WIRE_H
#define EASYLSP_DIAGNOSTIC_WIRE_H
#include <stdint.h>
#define SH_DIAG_VERSION 1
enum { SH_INIT = 1, SH_PROBE, SH_EXISTING, SH_BEFORE_INIT, SH_AFTER_INIT, SH_EXIT, SH_BEFORE_FINI };
struct sh_diag_event {
    uint32_t version, kind, pid, tid;
    uint64_t sequence, dropped, wall_ms, elapsed_ms, pc, a, b, c;
    uint32_t flags, reserved;
    char name[256];
};
#endif
