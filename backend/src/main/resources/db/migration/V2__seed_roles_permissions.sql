-- 角色与权限点种子数据。刻意不插入任何用户与密码：
-- 演示账号由 scripts/ 下的初始化脚本创建（密码来自环境变量），避免默认口令进入版本库。

insert into sys_role (id, code, name, description, created_at) values
    (1, 'ADMIN',     '系统管理员', '管理用户、全部 Agent 应用与安全策略，可查看全部日志与评测', current_timestamp),
    (2, 'DEVELOPER', '开发者',     '创建并配置自己的 Agent 应用，上传评测集、发起评测、查看自己的 Trace 与报告', current_timestamp),
    (3, 'AUDITOR',   '审计员',     '只读查看安全事件、评测结果与审计日志，可验证审计哈希链', current_timestamp);

insert into sys_permission (id, code, name, resource, action) values
    (1,  'user:manage',        '用户与角色管理',   'user',       'manage'),
    (2,  'app:read',           '查看 Agent 应用',  'app',        'read'),
    (3,  'app:create',         '创建 Agent 应用',  'app',        'create'),
    (4,  'app:update',         '修改 Agent 应用',  'app',        'update'),
    (5,  'app:delete',         '删除 Agent 应用',  'app',        'delete'),
    (6,  'policy:read',        '查看安全策略',     'policy',     'read'),
    (7,  'policy:update',      '修改安全策略',     'policy',     'update'),
    (8,  'agent:chat',         '调用 Agent',       'agent',      'chat'),
    (9,  'eval:read',          '查看评测',         'eval',       'read'),
    (10, 'eval:dataset:write', '上传评测集',       'eval',       'dataset:write'),
    (11, 'eval:run',           '发起评测任务',     'eval',       'run'),
    (12, 'trace:read',         '查看 Trace',       'trace',      'read'),
    (13, 'security:read',      '查看安全事件',     'security',   'read'),
    (14, 'audit:read',         '查看审计日志',     'audit',      'read'),
    (15, 'audit:verify',       '验证审计哈希链',   'audit',      'verify');

-- ADMIN：全部权限
insert into sys_role_permission (role_id, permission_id)
select 1, id from sys_permission;

-- DEVELOPER：应用、策略、对话、评测（不含用户管理、不含审计读取与验证）
insert into sys_role_permission (role_id, permission_id)
select 2, id from sys_permission where code in (
    'app:read','app:create','app:update','app:delete',
    'policy:read','policy:update','agent:chat',
    'eval:read','eval:dataset:write','eval:run',
    'trace:read','security:read'
);

-- AUDITOR：只读 + 审计验证，不能修改 Agent 与安全策略
insert into sys_role_permission (role_id, permission_id)
select 3, id from sys_permission where code in (
    'app:read','policy:read','eval:read','trace:read','security:read','audit:read','audit:verify'
);
