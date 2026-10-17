// Минимальная замена GoogleTest для сборки тестов без сети (-DMPC_MINI_GTEST=ON).
#pragma once
#include <cmath>
#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace mini {
struct Test { const char* suite; const char* name; std::function<void()> fn; };
inline std::vector<Test>& registry() { static std::vector<Test> r; return r; }
inline bool& failed() { static bool f = false; return f; }
struct Reg { Reg(const char* s, const char* n, std::function<void()> f) { registry().push_back({s, n, std::move(f)}); } };
struct Abort {};
inline void fail(const char* file, int line, const std::string& what, bool fatal) {
    std::printf("  %s:%d: %s\n", file, line, what.c_str());
    failed() = true;
    if (fatal) throw Abort{};
}
}  // namespace mini

#define TEST(suite, name)                                                         \
    static void suite##_##name();                                                 \
    static mini::Reg suite##_##name##_reg(#suite, #name, suite##_##name);         \
    static void suite##_##name()

#define MINI_CHECK(cond, text, fatal) \
    do { if (!(cond)) mini::fail(__FILE__, __LINE__, text, fatal); } while (0)

#define EXPECT_TRUE(c) MINI_CHECK((c), "EXPECT_TRUE(" #c ")", false)
#define EXPECT_FALSE(c) MINI_CHECK(!(c), "EXPECT_FALSE(" #c ")", false)
#define EXPECT_EQ(a, b) MINI_CHECK((a) == (b), "EXPECT_EQ(" #a ", " #b ")", false)
#define EXPECT_NE(a, b) MINI_CHECK((a) != (b), "EXPECT_NE(" #a ", " #b ")", false)
#define EXPECT_LT(a, b) MINI_CHECK((a) < (b), "EXPECT_LT(" #a ", " #b ")", false)
#define EXPECT_LE(a, b) MINI_CHECK((a) <= (b), "EXPECT_LE(" #a ", " #b ")", false)
#define EXPECT_GT(a, b) MINI_CHECK((a) > (b), "EXPECT_GT(" #a ", " #b ")", false)
#define EXPECT_GE(a, b) MINI_CHECK((a) >= (b), "EXPECT_GE(" #a ", " #b ")", false)
#define EXPECT_NEAR(a, b, eps) MINI_CHECK(std::fabs(double(a) - double(b)) <= double(eps), "EXPECT_NEAR(" #a ", " #b ")", false)
#define EXPECT_FLOAT_EQ(a, b) EXPECT_NEAR(a, b, 1e-5)
#define ASSERT_TRUE(c) MINI_CHECK((c), "ASSERT_TRUE(" #c ")", true)
#define ASSERT_FALSE(c) MINI_CHECK(!(c), "ASSERT_FALSE(" #c ")", true)
#define ASSERT_EQ(a, b) MINI_CHECK((a) == (b), "ASSERT_EQ(" #a ", " #b ")", true)
#define ASSERT_NE(a, b) MINI_CHECK((a) != (b), "ASSERT_NE(" #a ", " #b ")", true)
#define ASSERT_GE(a, b) MINI_CHECK((a) >= (b), "ASSERT_GE(" #a ", " #b ")", true)
#define ASSERT_GT(a, b) MINI_CHECK((a) > (b), "ASSERT_GT(" #a ", " #b ")", true)
#define ASSERT_LE(a, b) MINI_CHECK((a) <= (b), "ASSERT_LE(" #a ", " #b ")", true)

#ifdef MINI_GTEST_MAIN
int main() {
    int bad = 0;
    for (auto& t : mini::registry()) {
        mini::failed() = false;
        try { t.fn(); } catch (mini::Abort&) {}
        std::printf("[%s] %s.%s\n", mini::failed() ? "FAIL" : " OK ", t.suite, t.name);
        if (mini::failed()) ++bad;
    }
    std::printf("%zu tests, %d failed\n", mini::registry().size(), bad);
    return bad ? 1 : 0;
}
#endif
