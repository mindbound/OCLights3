-- Verify the library's Lua-5.2 double packer against string.pack(">d", v).
--
-- This path cannot be reached in game: OpenOS runs Lua 5.3, so string.pack always wins and the
-- fallback is dead code there. It is live only on a 5.2 architecture, which is exactly the
-- configuration nobody tests. So check it here, where a real interpreter exists.
--
-- Lua 5.4 removed math.frexp, so the test supplies one.

local function frexp(v)
  if v == 0 or v ~= v or v == math.huge or v == -math.huge then return v, 0 end
  local e = 0
  local m = v
  while math.abs(m) >= 1 do m = m / 2; e = e + 1 end
  while math.abs(m) < 0.5 do m = m * 2; e = e - 1 end
  return m, e
end

-- Verbatim copy of the fallback branch from lib/opengpu.lua.
local function packDoubleFallback(v)
  local sign = 0
  if v < 0 or (v == 0 and 1 / v < 0) then sign, v = 1, -v end
  local mantissa, exponent = 0, 0
  if v == math.huge then
    exponent = 2047
  elseif v ~= 0 then
    local m, e = frexp(v)
    exponent = e + 1022
    if exponent < 1 then
      m = m * 2 ^ exponent
      exponent = 0
    else
      m = m * 2 - 1
    end
    mantissa = math.floor(m * 2 ^ 52 + 0.5)
  end
  local b = {}
  for i = 8, 3, -1 do
    b[i] = string.char(mantissa % 256)
    mantissa = math.floor(mantissa / 256)
  end
  b[2] = string.char((exponent % 16) * 16 + mantissa % 16)
  b[1] = string.char(sign * 128 + math.floor(exponent / 16))
  return table.concat(b)
end

local function hex(s)
  return (s:gsub(".", function(c) return string.format("%02X", c:byte()) end))
end

local cases = {
  0.0, -0.0, 1.0, -1.0, 0.5, 2.0, 100.0, 160.0, 512.0, 288.0,
  0.1, -0.1, 3.14159265358979, 1e10, -1e10, 1e-10,
  255.0, 65535.0, 2 ^ 31, -(2 ^ 31), 2 ^ 52, 2 ^ 53,
  1.5, 0.3333333333333333, 1e308, 1e-308,
  5e-324,          -- smallest subnormal
  1e-310,          -- mid subnormal
  2.2250738585072014e-308,  -- smallest normal
}

local pass, fail = 0, 0
for _, v in ipairs(cases) do
  local want = string.pack(">d", v)
  local got = packDoubleFallback(v)
  if want == got then
    pass = pass + 1
  else
    fail = fail + 1
    print(string.format("MISMATCH %-26s want %s got %s", tostring(v), hex(want), hex(got)))
  end
end

print(string.format("packDouble: %d passed, %d failed", pass, fail))
os.exit(fail == 0 and 0 or 1)
