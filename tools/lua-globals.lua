-- Accidental-global scanner for the shipped Lua. Run from the repo root:
--
--     lua tools/lua-globals.lua [file ...]
--
-- WHY THIS EXISTS, AND WHY luac -p AND THE SMOKE HARNESS BOTH MISS IT.
--
-- In Lua an undefined identifier is not an error. It is a read of a global, which is nil, and it
-- compiles cleanly. So `luac -p` passes, and the smoke harness only notices if a test happens to
-- exercise the exact line -- which it usually does not, because the symptom is a branch that
-- never fires rather than a crash.
--
-- Two real defects in one day, both of this shape:
--
--   * `local Canvas` was declared BELOW Node's methods, so `getmetatable(other) == Canvas` inside
--     Node:swapWith compared against a global -- nil, always false. The guard's diagnostic branch
--     was dead code that read exactly like working code. Shipped, and found by review rather than
--     by any test.
--   * a benchmark renamed `sweeps` to `budget` and left one reference behind. That one DID crash,
--     but only at the end of a run, in game, after a minute of setup.
--
-- The check itself is the compiler's own answer: every global read is a GETTABUP against _ENV in
-- the bytecode, so disassembling and diffing the names against an allowlist finds them exactly,
-- with no parsing of Lua source and no false positives from strings or comments.

local ALLOWED = {}
for name in ([[
  -- Lua standard library, 5.3 and 5.4
  assert error getmetatable setmetatable ipairs pairs next print rawequal rawget rawlen rawset
  require select tonumber tostring type pcall xpcall unpack load loadstring dofile collectgarbage
  string table math os io coroutine utf8 debug bit32 package _G _VERSION
  -- OpenComputers / OpenOS globals available to a program
  computer component unicode checkArg
]]):gmatch("[%a_][%w_]*") do
  ALLOWED[name] = true
end
ALLOWED["end"] = nil   -- the comment word above, not a real name

local LUAC = os.getenv("LUAC") or "luac"

local targets = { ... }
if #targets == 0 then
  targets = {
    "src/main/resources/assets/opengpu/lua/v2/lib/opengpu.lua",
    "tools/lua-smoke.lua",
    "tools/lua-packcheck.lua",
  }
end

local bad, scanned = 0, 0
for _, path in ipairs(targets) do
  local pipe = io.popen(("%s -l -l %q 2>&1"):format(LUAC, path))
  if not pipe then
    print("FAIL  cannot run " .. LUAC .. " -- set $LUAC to its full path")
    os.exit(2)
  end
  local dump = pipe:read("a")
  pipe:close()

  if dump == "" or dump:find("cannot open", 1, true) then
    print("FAIL  " .. path .. " -- luac produced nothing (missing file?)")
    bad = bad + 1
  else
    scanned = scanned + 1
    -- Collect names in first-seen order so the report is stable and reads top-down.
    local seen, order = {}, {}
    for name in dump:gmatch('_ENV%s+"([%a_][%w_]*)"') do
      if not seen[name] then
        seen[name] = true
        order[#order + 1] = name
      end
    end
    local offenders = {}
    for _, name in ipairs(order) do
      if not ALLOWED[name] then
        offenders[#offenders + 1] = name
      end
    end
    if #offenders == 0 then
      print(("ok    %-58s %d global reads, all known"):format(path, #order))
    else
      bad = bad + 1
      print(("FAIL  %s reads %d unknown global(s):"):format(path, #offenders))
      for _, name in ipairs(offenders) do
        -- Point at the source line where the name appears, so the report is actionable. The
        -- bytecode knows the line but reporting it robustly means parsing luac's layout; a
        -- grep of the source is good enough to locate a name that occurs once or twice.
        local lines = {}
        local n = 0
        for line in io.lines(path) do
          n = n + 1
          if line:find("[^%w_]" .. name .. "[^%w_]") or line:find("^" .. name .. "[^%w_]") then
            lines[#lines + 1] = tostring(n)
          end
        end
        print(("        %-24s source line(s): %s"):format(name,
              #lines > 0 and table.concat(lines, ", ") or "?"))
      end
    end
  end
end

print("")
if bad == 0 then
  print(("=== %d file(s) clean ==="):format(scanned))
else
  print(("=== %d file(s) with unknown globals ==="):format(bad))
  print("A name here is either a typo, or a local declared BELOW its use -- Lua scopes a local")
  print("from its declaration onward, so naming it earlier silently reads a nil global instead.")
  print("Fix by declaring it earlier (forward-declare `local X` and assign `X = ...` later), or")
  print("add it to ALLOWED if it is genuinely a runtime-provided global.")
end
os.exit(bad == 0 and 0 or 1)
