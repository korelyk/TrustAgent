import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 为 TrustAgent 生成管理员口令的 bcrypt 哈希。
 *
 * <p>为什么需要它：项目**没有**"修改口令"接口——UserAdminController 只有
 * 创建用户 / 改角色 / 改状态三条路由。所以在线改口令只能直接写 sys_user.password_hash。
 *
 * <p>为什么必须用这个类而不是 python-bcrypt / htpasswd：
 * 后端用 BCryptPasswordEncoder 校验，它默认产出 {@code $2a$} 前缀、strength 10。
 * 其他实现产出 {@code $2b$}/{@code $2y$}。虽然 Spring Security 的 matches()
 * 通常能兼容几种前缀，但"通常兼容"不等于"一定兼容"——
 * 用同一个 jar、同一个类生成，就没有这个不确定性。
 *
 * <h2>用法</h2>
 * <pre>
 * # 1) 找到本地 Maven 仓库里的这个 jar（后端依赖里有，不需要额外下载）
 * CRYPTO=$(find ~/.m2 -name 'spring-security-crypto-*.jar' | head -1)
 * JCL=$(find ~/.m2 -name 'spring-jcl-*.jar' | head -1)      # BCryptPasswordEncoder 需要它
 *
 * # 2) 编译（javac -cp 只需要 crypto，运行时两个都要）
 * javac -cp "$CRYPTO" scripts/tools/GenHash.java
 *
 * # 3) 生成（会先跑 4 项自检，任何一项不过就非零退出且不输出哈希）
 * java -cp "$CRYPTO:$JCL:scripts/tools" GenHash '你的新口令'
 * </pre>
 *
 * <p>Windows 下 classpath 分隔符是 {@code ;} 而不是 {@code :}。
 *
 * <p>自检项：正确口令必须 matches、错误口令必须不 matches、
 * 两次生成结果必须不同（salt 随机化）、格式必须是 {@code $2a$} 且长度 60。
 * 没有这些自检，就等于闭着眼睛把一个可能无效的哈希写进库里——
 * 症状是"改完登不进去"，而原因完全看不出来。
 */
public final class GenHash {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("用法: java GenHash <口令>");
            System.exit(2);
        }
        String raw = args[0];
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        String hash = encoder.encode(raw);

        // 自检 1/2：正反两向都要对
        if (!encoder.matches(raw, hash)) {
            System.err.println("自检失败: 生成的口令无法通过校验");
            System.exit(1);
        }
        if (encoder.matches(raw + "x", hash)) {
            System.err.println("自检失败: 错误口令竟然通过了校验");
            System.exit(1);
        }

        // 自检 3：salt 必须随机化
        String hash2 = encoder.encode(raw);
        if (hash.equals(hash2)) {
            System.err.println("自检失败: 两次生成结果相同，salt 没有随机化");
            System.exit(1);
        }
        if (!encoder.matches(raw, hash2)) {
            System.err.println("自检失败: 第二次生成的哈希无法通过校验");
            System.exit(1);
        }

        // 自检 4：格式必须符合 bcrypt 规范
        if (!hash.startsWith("$2a$") || hash.length() != 60) {
            System.err.println("自检失败: 格式异常 prefix=" + hash.substring(0, 4)
                    + " len=" + hash.length());
            System.exit(1);
        }

        System.out.println(hash);
    }
}
