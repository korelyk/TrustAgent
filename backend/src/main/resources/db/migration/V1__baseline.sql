-- TrustAgent baseline schema.
-- 兼容 MySQL 8.0 与 H2 2.x (MODE=MySQL)：不使用 ENGINE/CHARSET/列注释/内联 UNIQUE KEY。
-- 表结构说明、索引理由、敏感字段约定见 docs/DATABASE.md。

-- ---------------------------------------------------------------------------
-- 身份与权限
-- ---------------------------------------------------------------------------
create table sys_user (
    id            bigint       not null auto_increment,
    username      varchar(64)  not null,
    password_hash varchar(100) not null,
    display_name  varchar(64),
    email         varchar(128),
    status        varchar(16)  not null,
    last_login_at timestamp    null,
    created_at    timestamp    not null,
    updated_at    timestamp    not null,
    primary key (id)
);
create unique index uk_sys_user_username on sys_user (username);

create table sys_role (
    id          bigint      not null auto_increment,
    code        varchar(32) not null,
    name        varchar(64) not null,
    description varchar(255),
    created_at  timestamp   not null,
    primary key (id)
);
create unique index uk_sys_role_code on sys_role (code);

create table sys_permission (
    id       bigint      not null auto_increment,
    code     varchar(64) not null,
    name     varchar(64) not null,
    resource varchar(64) not null,
    action   varchar(32) not null,
    primary key (id)
);
create unique index uk_sys_permission_code on sys_permission (code);

create table sys_user_role (
    id      bigint not null auto_increment,
    user_id bigint not null,
    role_id bigint not null,
    primary key (id)
);
create unique index uk_sys_user_role on sys_user_role (user_id, role_id);

create table sys_role_permission (
    id            bigint not null auto_increment,
    role_id       bigint not null,
    permission_id bigint not null,
    primary key (id)
);
create unique index uk_sys_role_permission on sys_role_permission (role_id, permission_id);

-- ---------------------------------------------------------------------------
-- Agent 应用 / 工具 / 安全策略
-- ---------------------------------------------------------------------------
create table agent_app (
    id            bigint       not null auto_increment,
    code          varchar(64)  not null,
    name          varchar(128) not null,
    description   varchar(512),
    owner_id      bigint       not null,
    system_prompt varchar(2000),
    status        varchar(16)  not null,
    created_at    timestamp    not null,
    updated_at    timestamp    not null,
    primary key (id)
);
create unique index uk_agent_app_code on agent_app (code);
create index idx_agent_app_owner on agent_app (owner_id);

create table agent_tool (
    id           bigint       not null auto_increment,
    app_id       bigint       not null,
    tool_name    varchar(64)  not null,
    enabled      tinyint      not null,
    param_schema varchar(4000),
    description  varchar(512),
    created_at   timestamp    not null,
    updated_at   timestamp    not null,
    primary key (id)
);
create unique index uk_agent_tool_app_name on agent_tool (app_id, tool_name);

create table security_policy (
    id                            bigint      not null auto_increment,
    app_id                        bigint      not null,
    version                       int         not null,
    guard_enabled                 tinyint     not null,
    max_tool_calls                int         not null,
    max_input_length              int         not null,
    task_timeout_ms               int         not null,
    domain_whitelist              varchar(1024),
    denied_param_rules            varchar(1024),
    confirm_side_effects          tinyint     not null,
    injection_detection_enabled   tinyint     not null,
    pii_masking_enabled           tinyint     not null,
    output_guard_enabled          tinyint     not null,
    created_at                    timestamp   not null,
    updated_at                    timestamp   not null,
    primary key (id)
);
create unique index uk_security_policy_app on security_policy (app_id);

-- ---------------------------------------------------------------------------
-- 评测
-- ---------------------------------------------------------------------------
create table eval_dataset (
    id              bigint       not null auto_increment,
    name            varchar(128) not null,
    description     varchar(512),
    source_category varchar(128) not null,
    checksum        varchar(64),
    case_count      int          not null,
    owner_id        bigint       not null,
    created_at      timestamp    not null,
    primary key (id)
);
create index idx_eval_dataset_owner on eval_dataset (owner_id);

create table eval_case (
    id                      bigint      not null auto_increment,
    dataset_id              bigint      not null,
    case_id                 varchar(64) not null,
    category                varchar(48) not null,
    prompt                  text        not null,
    context                 text,
    expected_action         varchar(32) not null,
    expected_tool           varchar(64),
    contains_sensitive_data tinyint     not null,
    tags                    varchar(512),
    created_at              timestamp   not null,
    primary key (id)
);
create unique index uk_eval_case_dataset_case on eval_case (dataset_id, case_id);
create index idx_eval_case_category on eval_case (category);

create table eval_run (
    id                  bigint      not null auto_increment,
    dataset_id          bigint      not null,
    app_id              bigint      not null,
    owner_id            bigint      not null,
    mode                varchar(16) not null,
    status              varchar(16) not null,
    total_cases         int         not null,
    completed_cases     int         not null,
    failed_cases        int         not null,
    attempts            int         not null,
    idempotency_key     varchar(128),
    guard_metrics_json  text,
    baseline_metrics_json text,
    error_message       varchar(512),
    trace_id            varchar(64),
    created_at          timestamp   not null,
    started_at          timestamp   null,
    finished_at         timestamp   null,
    primary key (id)
);
create unique index uk_eval_run_idem on eval_run (idempotency_key);
create index idx_eval_run_status on eval_run (status);
create index idx_eval_run_owner on eval_run (owner_id);

create table eval_case_result (
    id                  bigint      not null auto_increment,
    run_id              bigint      not null,
    case_id             varchar(64) not null,
    category            varchar(48) not null,
    guard_enabled       tinyint     not null,
    expected_action     varchar(32),
    actual_action       varchar(32),
    expected_tool       varchar(64),
    actual_tool         varchar(64),
    blocked             tinyint     not null,
    detected_categories varchar(512),
    pii_leaked          tinyint     not null,
    passed              tinyint     not null,
    latency_ms          int         not null,
    detail_json         text,
    error_message       varchar(512),
    created_at          timestamp   not null,
    primary key (id)
);
create unique index uk_eval_result_run_case_guard on eval_case_result (run_id, case_id, guard_enabled);
create index idx_eval_result_run on eval_case_result (run_id);

-- ---------------------------------------------------------------------------
-- 运行时留痕
-- ---------------------------------------------------------------------------
create table agent_trace (
    id                bigint       not null auto_increment,
    trace_id          varchar(64)  not null,
    app_id            bigint,
    user_id           bigint,
    request_id        varchar(64),
    input_preview     varchar(1000),
    output_preview    varchar(1000),
    guard_decision    varchar(32),
    guard_categories  varchar(512),
    status            varchar(16)  not null,
    tool_call_count   int          not null,
    total_latency_ms  int          not null,
    error_message     varchar(512),
    created_at        timestamp    not null,
    primary key (id)
);
create unique index uk_agent_trace_trace_id on agent_trace (trace_id);
create index idx_agent_trace_app_created on agent_trace (app_id, created_at);
create index idx_agent_trace_user_created on agent_trace (user_id, created_at);

create table tool_call_record (
    id               bigint       not null auto_increment,
    trace_id         varchar(64)  not null,
    tool_name        varchar(64)  not null,
    arguments_masked varchar(2000),
    result_masked    varchar(2000),
    allowed          tinyint      not null,
    deny_reason      varchar(255),
    latency_ms       int          not null,
    created_at       timestamp    not null,
    primary key (id)
);
create index idx_tool_call_trace on tool_call_record (trace_id);

create table security_event (
    id               bigint       not null auto_increment,
    trace_id         varchar(64),
    app_id           bigint,
    event_type       varchar(48)  not null,
    severity         varchar(16)  not null,
    detector         varchar(48),
    rule_id          varchar(64),
    evidence_masked  varchar(1000),
    action_taken     varchar(32),
    created_at       timestamp    not null,
    primary key (id)
);
create index idx_security_event_created on security_event (created_at);
create index idx_security_event_app_type on security_event (app_id, event_type);

create table audit_log (
    id             bigint       not null auto_increment,
    seq            bigint       not null,
    actor_id       bigint,
    actor_username varchar(64),
    action         varchar(64)  not null,
    resource_type  varchar(48),
    resource_id    varchar(64),
    detail_json    varchar(2000),
    trace_id       varchar(64),
    prev_hash      varchar(64),
    record_hash    varchar(64)  not null,
    created_at     timestamp    not null,
    -- 哈希计算使用的时间来源：epoch 毫秒的整数值。原因见 docs/DATABASE.md：
    -- timestamp 列的秒/微秒精度由数据库决定，若直接用列值参与哈希，读回时可能被截断导致“验证误报”。
    created_at_epoch_ms bigint  not null,
    primary key (id)
);
create unique index uk_audit_log_seq on audit_log (seq);
create index idx_audit_log_action on audit_log (action);
create index idx_audit_log_created on audit_log (created_at);

create table idempotency_record (
    id            bigint       not null auto_increment,
    idem_key      varchar(128) not null,
    scope         varchar(64)  not null,
    request_hash  varchar(64),
    resource_type varchar(48),
    resource_id   varchar(64),
    status        varchar(16)  not null,
    created_at    timestamp    not null,
    expires_at    timestamp    not null,
    primary key (id)
);
create unique index uk_idem_key_scope on idempotency_record (idem_key, scope);
create index idx_idem_expires on idempotency_record (expires_at);
