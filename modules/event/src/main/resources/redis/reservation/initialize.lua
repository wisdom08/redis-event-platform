local stateType = redis.call('TYPE', KEYS[1]).ok
local streamType = redis.call('TYPE', KEYS[2]).ok
if stateType == 'hash' then
    if redis.call('HGET', KEYS[1], 'total') == ARGV[1]
        and redis.call('HGET', KEYS[1], 'startsAt') == ARGV[2]
        and redis.call('HGET', KEYS[1], 'endsAt') == ARGV[3] then
        return 'EXISTS'
    end
    return 'CONFIG_CONFLICT'
end
-- Stream만 남은 상태를 새 이벤트처럼 초기화하지 않는다.
if stateType ~= 'none' or streamType ~= 'none' then return 'STATE_INVALID' end
redis.call('HSET', KEYS[1], 'total', ARGV[1], 'remaining', ARGV[1],
    'startsAt', ARGV[2], 'endsAt', ARGV[3])
return 'CREATED'
