package io.trustagent.user;

/** 账号状态常量。用常量而非枚举，是为了和数据库里的 varchar 值一一对应、避免 MyBatis 需要额外 TypeHandler。 */
public final class UserStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";

    private UserStatus() {
    }
}
