#include <sys/mman.h>

#include <cmath>
#include <cstring>

#include "check.h"
#include "zb/guest_memory.h"

int main() {
    zb::GuestMemory m;
    CHECK(m.ok());
    CHECK(!m.accessible(0, 1, zb::kPageRead));

    CHECK(m.map_anon(0x10000, 5000, PROT_READ | PROT_WRITE));
    CHECK(m.accessible(0x10000, 8192, zb::kPageRead | zb::kPageWrite));
    CHECK(!m.accessible(0x10000, 8193, zb::kPageRead));
    m.base()[0x11fff] = 7;
    CHECK(m.host_ptr(0x11fff, 1, zb::kPageRead)[0] == 7);

    CHECK(m.protect(0x10000, 4096, PROT_READ | PROT_EXEC));
    CHECK(m.host_ptr(0x10000, 1, zb::kPageWrite) == nullptr);
    CHECK(m.accessible(0x10000, 4, zb::kPageExec));
    CHECK(!m.protect(0x20000, 4096, PROT_READ));
    CHECK(!m.map_anon(0x10001, 10, PROT_READ));
    CHECK(!m.accessible(0xFFFFFFF0, 32, 0));

    std::uint32_t f = m.find_free(3 * 4096, 0x40000000);
    CHECK(f != 0 && (f & 0xFFF) == 0 && f + 3 * 4096 <= 0x40000000);
    CHECK(m.range_free(f, 3 * 4096));

    CHECK(m.unmap(0x10000, 8192));
    CHECK(!m.accessible(0x10000, 1, 0));
    CHECK(m.find_free(4096, 0x11000) == 0x10000);
    CHECK(m.find_free(4096, 0x10000) == 0);

    // Memory editor: scan writable pages only, read back, write, rescan.
    using T = zb::GuestMemory::ValueType;
    CHECK(zb::GuestMemory::current() == &m);
    CHECK(m.map_anon(0x30000, 8192, PROT_READ | PROT_WRITE));
    CHECK(m.map_anon(0x40000, 4096, PROT_READ | PROT_WRITE));
    const std::int32_t coins = 123457;
    const float speed = 2.5f;
    std::memcpy(m.base() + 0x30010, &coins, 4);
    std::memcpy(m.base() + 0x31ffc, &coins, 4);
    std::memcpy(m.base() + 0x40000, &coins, 4);
    CHECK(m.protect(0x40000, 4096, PROT_READ));  // read-only: not offered
    std::memcpy(m.base() + 0x30100, &speed, 4);
    auto hits = m.scan(T::Int32, coins, 0, 100);
    CHECK(hits.size() == 2 && hits[0] == 0x30010 && hits[1] == 0x31ffc);
    CHECK(m.scan(T::Int32, coins, 0, 1).size() == 1);
    CHECK(m.scan(T::Float, 2.5, 0.05, 100) == std::vector<std::uint32_t>{0x30100});
    CHECK(m.write_value(T::Int32, 0x30010, 999));
    CHECK(!m.write_value(T::Int32, 0x40000, 999));
    auto values = m.read_values(T::Int32, {0x30010, 0x31ffc, 0x50000});
    CHECK(values[0] == 999 && values[1] == coins && std::isnan(values[2]));

    std::puts("guest_memory_test PASS");
    return 0;
}
