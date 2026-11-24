local stateType = redis.call('TYPE', KEYS[1]).ok
local streamType = redis.call('TYPE', KEYS[2]).ok
if stateType == 'none' then return 'NOT_INITIALIZED' end
if stateType ~= 'hash' or (streamType ~= 'none' and streamType ~= 'stream') then
    return 'STATE_INVALID'
end

local total = tonumber(redis.call('HGET', KEYS[1], 'total'))
local remaining = tonumber(redis.call('HGET', KEYS[1], 'remaining'))
local startsAt = redis.call('HGET', KEYS[1], 'startsAt')
local endsAt = redis.call('HGET', KEYS[1], 'endsAt')
if not total or not remaining or total < 1 or total > 2147483647
    or total ~= math.floor(total) or remaining ~= math.floor(remaining)
    or remaining < 0 or remaining > total or not startsAt or not endsAt
    or #startsAt ~= 27 or #endsAt ~= 27 or startsAt >= endsAt then
    return 'STATE_INVALID'
end
-- 이번 단계에서는 TTL·삭제·트리밍을 허용하지 않는다. 부분 유실을 감지하면 중단한다.
local reserved = total - remaining
local length = 0
if streamType == 'stream' then length = redis.call('XLEN', KEYS[2]) end
if redis.call('HLEN', KEYS[1]) ~= 4 + reserved or length ~= reserved then
    return 'STATE_INVALID'
end

local userField = 'user:' .. ARGV[2]
local existing = redis.call('HGET', KEYS[1], userField)
if existing then return 'REPLAY:' .. existing end
-- Java가 생성한 UTC 고정 길이 마이크로초 문자열을 비교한다.
if ARGV[3] < startsAt then return 'NOT_STARTED' end
if ARGV[3] >= endsAt then return 'ENDED' end
if remaining == 0 then return 'SOLD_OUT' end

-- Lua는 자동 롤백하지 않는다. XADD 실패 시 재고를 건드리지 않도록 먼저 기록한다.
-- 이후 HSET은 재고와 사용자 기록을 하나의 명령으로 변경한다.
if not redis.acl_check_cmd('HSET', KEYS[1], 'remaining', tostring(remaining - 1), userField, 'id')
    or not redis.acl_check_cmd('XADD', KEYS[2], '*', 'eventId', ARGV[1], 'userId', ARGV[2])
    or not redis.acl_check_cmd('XDEL', KEYS[2], '0-1') then
    return 'PERMISSION_DENIED'
end
local message = redis.pcall('XADD', KEYS[2], '*',
    'eventId', ARGV[1], 'userId', ARGV[2], 'reservedAt', ARGV[3], 'schemaVersion', '1')
if type(message) == 'table' and message.err then return 'STREAM_WRITE_FAILED' end
local updated = redis.pcall('HSET', KEYS[1], 'remaining', tostring(remaining - 1), userField, message)
if type(updated) == 'table' and updated.err then
    local removed = redis.pcall('XDEL', KEYS[2], message)
    if type(removed) == 'table' and removed.err then return 'RECONCILIATION_REQUIRED' end
    return 'STATE_WRITE_FAILED'
end
return 'CREATED:' .. message
