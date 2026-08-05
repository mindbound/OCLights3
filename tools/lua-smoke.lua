-- Headless smoke test of lib/opengpu.lua against a stubbed component.
--
-- The library ships on a loot disk and has never had automated coverage of any kind: no JVM
-- test can load Lua, the Gradle build does not parse resources, and CI never boots a computer.
-- A chaining bug already shipped because of that. This runs the real file against a fake
-- component that records calls, so at least the logic that does not need Minecraft is checked.

package.path = package.path .. ";./src/main/resources/assets/opengpu/lua/v2/lib/?.lua"

-- ---- stubs -----------------------------------------------------------------
local calls = {}
local nextId = 0
local function record(name)
  return setmetatable({}, { __call = function(_, ...)
    calls[#calls + 1] = { name = name, args = table.pack(...) }
    if name == "createCanvas" or name == "createCanvasNode" or name == "createSprite" then
      nextId = nextId + 1
      return nextId
    elseif name == "canvasOps" then
      return {
        fill = { op = 1, args = 0 }, plot = { op = 2, args = 2 }, line = { op = 3, args = 4 },
        rectangle = { op = 4, args = 4 }, filledRectangle = { op = 5, args = 4 },
        triangle = { op = 6, args = 6 }, filledTriangle = { op = 7, args = 6 },
        oval = { op = 8, args = 4 }, filledOval = { op = 9, args = 4 },
        clearRectangle = { op = 10, args = 4 }, drawText = { op = 11, args = 2 },
        drawTexture = { op = 12, args = 3 }, drawTextureSub = { op = 13, args = 7 },
        setColor = { op = 14, args = 4 }, translate = { op = 15, args = 2 },
        rotate = { op = 16, args = 1 }, rotateAround = { op = 17, args = 3 },
        scale = { op = 18, args = 2 }, push = { op = 19, args = 0 },
        pop = { op = 20, args = 0 }, origin = { op = 21, args = 0 },
      }
    elseif name == "getEpoch" then
      return 12345
    elseif name == "getLimits" then
      return {
        submitBytes = 65536, submitBytesPerTick = 131072, commandCap = 4096,
        textChars = 8192, writeBytes = 16384, writeBytesPerTick = 16384,
        textureDim = 8192, standingCommandBytes = 2097152,
      }
    elseif name == "getVersion" then
      return { api = 2, protocol = 3, mod = "0.0.0-stub" }
    elseif name == "clearNodes" then
      return 0
    elseif name == "canvasSubmit" then
      return true
    end
  end })
end

local proxy = { address = "stub-address", type = "opengpu" }
for _, m in ipairs({ "canvasOps", "getEpoch", "clearNodes", "createCanvas", "createCanvasNode",
                     "createSprite", "canvasSubmit", "setNodeTransform", "setNodeZ",
                     "setNodeVisible", "setNodeTint", "freeNode", "freeCanvas",
                     "getSubmitBudget", "setColor", "fill", "clear", "drawText",
                     "getLimits", "getVersion", "swapVisibility" }) do
  proxy[m] = record(m)
end

-- A component that answers getLimits with a DIFFERENT per-call ceiling. The library must chunk
-- by whatever the server says, not by a number compiled into the library -- that hardcoding is
-- what this callback exists to remove, so a test that only ever sees the default value would
-- pass just as happily against the bug.
local tinyProxy = { address = "tiny-address", type = "opengpu" }
for k, v in pairs(proxy) do tinyProxy[k] = v end
tinyProxy.getLimits = setmetatable({}, { __call = function()
  return { submitBytes = 1024, submitBytesPerTick = 2048, commandCap = 4096, textChars = 40 }
end })

-- A component predating both callbacks. Must still bind, on the shipped fallbacks.
local oldProxy = { address = "old-address", type = "opengpu" }
for k, v in pairs(proxy) do oldProxy[k] = v end
oldProxy.getLimits = nil
oldProxy.getVersion = nil

package.loaded["component"] = setmetatable({ opengpu = proxy, proxy = function(a)
                                                 if a == "stub-address" then return proxy end
                                                 if a == "tiny-address" then return tinyProxy end
                                                 if a == "old-address" then return oldProxy end
                                                 return nil, "no such component"
                                               end },
                                           { __index = function() return nil end })
package.loaded["computer"] = { uptime = function() return os.clock() end }

-- ---- tests -----------------------------------------------------------------
local pass, fail = 0, 0
local function check(ok, what, extra)
  if ok then pass = pass + 1; print("  ok   " .. what)
  else fail = fail + 1; print("  FAIL " .. what .. (extra and ("  -- " .. tostring(extra)) or "")) end
end

local opengpu = require("opengpu")
check(type(opengpu.bind) == "function", "module loads and exposes bind()")

local gpu = opengpu.bind()
check(gpu ~= nil, "bind() returns a gpu")

-- The README / doc-comment example, verbatim. This is the one that shipped broken.
local c = gpu:canvas(160, 120)
local okChain, errChain = pcall(function()
  c:setColor(200, 40, 40):fillRect(0, 0, 160, 120)
  c:setColor(255, 255, 255):text(8, 8, "hello")
end)
check(okChain, "the documented chaining example runs", errChain)

local bytes, chunks = c:pending()
check(bytes == 4 + 33 + 33 + 33 + (1 + 16 + 2 + 5), "pending() reports the exact encoded size", bytes)
check(chunks == 1, "a small frame is one chunk", chunks)

check(c:publish() == true, "publish() succeeds")
check(select(1, c:pending()) == 4, "the buffer is reset after publish")

-- Chunking: 4096 filledRectangles is far past one submit and must split, not raise.
local big = gpu:canvas(512, 288)
for i = 1, 3000 do big:fillRect(i % 500, 0, 4, 4) end
local bigBytes, bigChunks = big:pending()
check(bigBytes > 65536, "3000 rects exceed one submit", bigBytes)
check(bigChunks > 1, "and are split into chunks", bigChunks)

local before = #calls
check(big:publish() == true, "an oversized frame publishes by chunking")
local submits, modes = 0, {}
for i = before + 1, #calls do
  if calls[i].name == "canvasSubmit" then
    submits = submits + 1
    modes[#modes + 1] = calls[i].args[2]
  end
end
check(submits == bigChunks, "one submit per chunk", submits .. " vs " .. bigChunks)
check(modes[1] == "publish", "the first chunk publishes")
local restAppend = true
for i = 2, #modes do if modes[i] ~= "append" then restAppend = false end end
check(restAppend, "every later chunk appends, so the first is not overwritten")

-- Guard rails
check(not pcall(function() c:fillRect(0, 0, "x", 1) end), "a non-numeric coordinate raises")
check(not pcall(function() c:fillRect(0, 0, 0 / 0, 1) end), "NaN raises")
check(not pcall(function() c:drawTexture(c, 0, 0) end), "drawTexture refuses a canvas handle")
check(not pcall(function() c:text(0, 0, "\xF0\x9F\x98\x80") end), "astral text is refused")
check(not pcall(function() c:text(0, 0, "\xFF\xFE") end), "malformed UTF-8 is refused")
check(pcall(function() c:text(0, 0, "caf\xC3\xA9") end), "valid 2-byte UTF-8 is accepted")
c:discard()

local node = gpu:show(c)
check(node ~= nil, "show() returns a node")
check(node:moveTo(10, 20) == node, "moveTo chains")
check(not pcall(function() node:setTint(255, 0, 0) end), "setTint refuses a canvas node")

node:free()
check(not pcall(function() node:moveTo(1, 1) end), "a freed node raises on use")
node:free()
check(true, "free() is idempotent")

check(not pcall(function() opengpu.bind("no-such-address") end),
      "bind() with a bad address raises instead of silently using the primary")

-- ---- version and limits discovery ------------------------------------------

local lim = gpu:limits()
check(type(lim) == "table" and lim.submitBytes == 65536, "limits() reports the per-call ceiling")
check(lim.submitBytesPerTick == 131072, "limits() reports the per-tick allowance")
check(lim.submitBytes ~= lim.submitBytesPerTick,
      "the two submit bounds stay DISTINCT -- collapsing them is the defect they encode")
lim.submitBytes = 1
check(gpu:limits().submitBytes == 65536, "limits() returns a copy, not the live table")

local ver = gpu:version()
check(type(ver) == "table" and ver.api == 2, "version() reports the api level")
check(ver.protocol == 3 and ver.mod == "0.0.0-stub", "version() reports protocol and mod")
ver.api = 99
check(gpu:version().api == 2, "version() returns a copy, not the live table")

-- The load-bearing one: chunking must follow the SERVER's ceiling.
local tiny = opengpu.bind("tiny-address")
check(tiny:limits().submitBytes == 1024, "a server-supplied ceiling overrides the fallback")
local tc = tiny:canvas(64, 64)
for i = 1, 40 do tc:setColor(i, i, i):fillRect(i, i, 2, 2) end
local tbytes, tchunks = tc:pending()
check(tchunks == math.max(1, math.ceil(tbytes / (1024 - 4))),
      "chunk count follows the server ceiling, not a compiled-in 64 KiB", tbytes .. "B/" .. tchunks)
check(tchunks > 1, "and that frame really does span more than one chunk", tchunks)
check(not pcall(function() tc:text(0, 0, string.rep("x", 41)) end),
      "a server-supplied textChars cap is enforced too")
tc:discard()

-- ---- the double-buffer swap ------------------------------------------------

local front = gpu:show(gpu:canvas(32, 32))
local back = gpu:show(gpu:canvas(32, 32))
back:setVisible(false)

local before = #calls
check(front:swapWith(back) == back, "swapWith returns the node now on screen, so it chains")
check(#calls == before + 1, "and it is ONE call, not two setVisible -- two could land in "
      .. "different batches, which is a frame of both-hidden or both-shown",
      (#calls - before) .. " calls")
check(calls[#calls].name == "swapVisibility", "it calls swapVisibility")
check(calls[#calls].args[1] == front.id and calls[#calls].args[2] == back.id,
      "with hide-then-show argument order")

check(not pcall(function() front:swapWith(front) end), "swapping a node with itself is refused")
check(not pcall(function() front:swapWith(nil) end), "swapWith(nil) is refused")
check(not pcall(function() front:swapWith({ id = 7 }) end),
      "swapWith refuses a table that is not a node")

local strayGpu = opengpu.bind("old-address")
local stray = strayGpu:show(strayGpu:canvas(16, 16))
check(not pcall(function() front:swapWith(stray) end),
      "swapWith refuses a node belonging to a DIFFERENT gpu -- node ids are scene-scoped, so "
      .. "the ids would collide silently and reveal the wrong node")
stray:free()

back:free()
front:free()
check(not pcall(function() front:swapWith(back) end), "a freed node cannot be swapped")

-- An older component must still work, on the fallbacks.
local old = opengpu.bind("old-address")
check(old:limits().submitBytes == 65536, "a component without getLimits falls back, not fails")
check(old:version() == nil, "version() is nil rather than fabricated when unsupported")

print(string.format("=== %d passed, %d failed ===", pass, fail))
os.exit(fail == 0 and 0 or 1)
