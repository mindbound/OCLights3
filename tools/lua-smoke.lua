-- Headless smoke test of lib/opengpu.lua against a stubbed component.
--
-- The library ships on a loot disk and has never had automated coverage of any kind: no JVM
-- test can load Lua, the Gradle build does not parse resources, and CI never boots a computer.
-- A chaining bug already shipped because of that. This runs the real file against a fake
-- component that records calls, so at least the logic that does not need Minecraft is checked.

package.path = package.path .. ";./src/main/resources/assets/opengpu/lua/v2/lib/?.lua"

-- ---- stubs -----------------------------------------------------------------
local calls = {}
--[[
  TWO id counters, because the server has two.

  SceneState allocates resource ids and node ids from independent sequences, so a canvas id and a
  node id are routinely the SAME small integer for different objects. A stub with one shared
  counter cannot represent that, and so cannot expose any confusion between the two spaces -- it
  makes every id globally unique, which is the one property the real server does not have. That
  blind spot is exactly why five swap checks passed against a guard that accepted a Canvas handle
  and posted its RESOURCE id as a NODE id.

  They are deliberately OFFSET as well as separate, so a test that mixes them up gets a wrong
  answer rather than an accidentally-right one.
]]
local nextResourceId, nextNodeId = 0, 100
local function record(name)
  return setmetatable({}, { __call = function(_, ...)
    calls[#calls + 1] = { name = name, args = table.pack(...) }
    if name == "createCanvas" then
      nextResourceId = nextResourceId + 1
      return nextResourceId
    elseif name == "createCanvasNode" or name == "createSprite" then
      nextNodeId = nextNodeId + 1
      return nextNodeId
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
        -- THIS TABLE IS A HAND-MAINTAINED MIRROR OF V2Wire's, and it drifted the moment
        -- OP_SET_FONT landed: the op existed on the server for a day while this stub still
        -- stopped at 21, so any smoke check of Canvas:setFont would have failed against the
        -- FIXTURE rather than the library. Add an op here in the same change that adds it to
        -- V2Wire. The library looks op ids up through canvasOps precisely so it never hardcodes
        -- them, which means a stale stub is indistinguishable from a broken library.
        setFont = { op = 22, args = 1 },
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
    elseif name == "getTextWidth" then
      -- Deliberately ARTIFICIAL, and encoding the font id in the answer. This stub tests the
      -- library's argument PLUMBING -- did the font the caller selected reach the server call --
      -- and real advances would make that unprovable, because both shipped fonts are 8px wide
      -- for ASCII and so a wrong font would return the right number. Real metrics are covered by
      -- FontMetricsGoldenTest on the JVM side and by ingame/fonttest.lua.
      local args = table.pack(...)
      return #tostring(args[1]) * 8 + (args[2] or 0) * 10000
    elseif name == "getFontMetrics" then
      local args = table.pack(...)
      return 8, (args[1] == 1) and 8 or 16
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
                     "getLimits", "getVersion", "swapVisibility",
                     "setFont", "getFontMetrics", "getTextWidth" }) do
  proxy[m] = record(m)
end

-- A component that answers getLimits with a DIFFERENT per-call ceiling. The library must chunk
-- by whatever the server says, not by a number compiled into the library -- that hardcoding is
-- what this callback exists to remove, so a test that only ever sees the default value would
-- pass just as happily against the bug.
-- NOTE the address is re-set AFTER the copy loop, in this fixture and the one below. `pairs(proxy)`
-- copies proxy.address too, so setting it first is silently undone -- every derived proxy then
-- claims to be the primary GPU. That masked the cross-GPU guard: the wrapper tables differed while
-- the addresses were accidentally identical, so an identity check passed for the wrong reason and
-- an address check failed for the right one.
local tinyProxy = { type = "opengpu" }
for k, v in pairs(proxy) do tinyProxy[k] = v end
tinyProxy.address = "tiny-address"
tinyProxy.getLimits = setmetatable({}, { __call = function()
  return { submitBytes = 1024, submitBytesPerTick = 2048, commandCap = 4096, textChars = 40 }
end })

-- A component predating both callbacks. Must still bind, on the shipped fallbacks.
local oldProxy = { type = "opengpu" }
for k, v in pairs(proxy) do oldProxy[k] = v end
oldProxy.address = "old-address"
oldProxy.getLimits = nil
oldProxy.getVersion = nil
-- Also predates swapVisibility. The fixture previously kept it, so the stale-proxy path -- the
-- one that has actually bitten in game, where OpenOS serves a cached proxy after a jar update --
-- was unreachable by the suite, and hit "attempt to call a nil value" inside the library.
oldProxy.swapVisibility = nil
-- And predates setFont, for the same reason: the stale-proxy path is the one that actually bites
-- in development, and a wrapper that calls a nil callback fails with "attempt to call a nil
-- value" instead of telling you to reboot the computer.
oldProxy.setFont = nil

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
-- Astral text is ACCEPTED since the move to Unifont, which carries glyphs above the BMP. The
-- library re-encodes the 4-byte UTF-8 form into the surrogate pair that the server's readUTF
-- expects; this used to be refused outright. Both an emoji (wide) and U+1D400 (narrow) so a
-- regression that mishandled one width would not hide behind the other.
check(pcall(function() c:text(0, 0, "\xF0\x9F\x98\x80") end), "astral text is accepted (emoji)")
check(pcall(function() c:text(0, 0, "\xF0\x9D\x90\x80") end), "astral text is accepted (U+1D400)")
check(pcall(function() c:text(0, 0, "a\xF0\x9F\x98\x80b") end), "astral text mixed with ASCII")
-- A 4-byte lead with a bad continuation must still be caught: the astral path decodes a
-- codepoint by arithmetic, and feeding it garbage would produce a plausible-looking pair.
check(not pcall(function() c:text(0, 0, "\xF0\x28\x8C\x28") end), "malformed 4-byte UTF-8 is refused")
check(not pcall(function() c:text(0, 0, "\xF5\x80\x80\x80") end), "out-of-range lead byte is refused")
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

-- ---- fonts -----------------------------------------------------------------
--
-- The font API shipped on 2026-08-08 with NO coverage here at all, which is how three defects
-- reached a play-test. What this section can check is the library's argument plumbing: that the
-- font a caller selects reaches the server call, and that the name-to-id mapping is not silently
-- lenient. What it CANNOT check is whether the widths are right -- that needs real font records,
-- and lives in FontMetricsGoldenTest and ingame/fonttest.lua.
--
-- The stub encodes the font id into getTextWidth's answer on purpose. Both shipped fonts are 8px
-- wide for ASCII, so a plumbing bug that forwarded the WRONG font would return the RIGHT number
-- against realistic values -- the fixture has to be able to tell them apart or the check is
-- decorative.

local FONT_MARKER = 10000  -- matches the stub: width = #text * 8 + fontId * FONT_MARKER

check(gpu:textWidth("abcd") == 4 * 8, "textWidth defaults to unifont (id 0)")
check(gpu:textWidth("abcd", "unscii8") == 4 * 8 + FONT_MARKER,
      "textWidth forwards the selected font", tostring(gpu:textWidth("abcd", "unscii8")))
check(gpu:textWidth("abcd", 1) == 4 * 8 + FONT_MARKER, "textWidth accepts a raw wire id")
check(gpu:textWidth("abcd", "unifont") == gpu:textWidth("abcd"),
      "naming the default is the same as omitting it")
check(not pcall(function() return gpu:textWidth("x", "comic-sans") end),
      "an unknown font NAME raises rather than defaulting")

local mw, mh = gpu:fontMetrics("unscii8")
check(mw == 8 and mh == 8, "fontMetrics forwards the font", ("%sx%s"):format(mw, mh))
local uw, uh = gpu:fontMetrics()
check(uw == 8 and uh == 16, "fontMetrics defaults to unifont")
check(mh ~= uh, "the two fonts report DIFFERENT line pitch -- the reason this call exists")

-- Immediate mode. Added because the display canvas had no setFont wrapper for a day: the
-- callback, the op and Canvas:setFont all existed while the most obvious spelling did not.
check(gpu:setFont("unscii8") == gpu, "gpu:setFont chains")
check(calls[#calls].name == "setFont" and calls[#calls].args[1] == 1,
      "gpu:setFont posts the resolved id, not the name")
check(not pcall(function() gpu:setFont("comic-sans") end), "gpu:setFont refuses an unknown name")
check(not pcall(function() gpu:setFont() end), "gpu:setFont refuses a missing font")

-- Canvas-level. The op id comes from canvasOps, never a literal, so this also fails if the
-- stub's op table drifts from V2Wire's.
local fc = gpu:canvas(64, 64)
check(fc:setFont("unscii8") == fc, "Canvas:setFont chains")
check(fc:textWidth("abcd") == 4 * 8 + FONT_MARKER,
      "Canvas:textWidth measures with the canvas's OWN font, not the default")
local plain = gpu:canvas(64, 64)
check(plain:textWidth("abcd") == 4 * 8,
      "a canvas that selected no font measures as unifont")
check(not pcall(function() fc:setFont("comic-sans") end), "Canvas:setFont refuses an unknown name")
fc:text(0, 0, "hi")
fc:publish()
local sawSetFont = false
for i = 1, #calls do
  if calls[i].name == "canvasSubmit" then sawSetFont = true end
end
check(sawSetFont, "a canvas carrying setFont still submits")
fc:free()
plain:free()

-- A component predating setFont must say so rather than calling nil, and must say REBOOT --
-- OpenOS caches the proxy and OC persists Lua state, so a client restart does not rebuild it.
-- Reuses the existing old-address fixture, which exists for exactly this.
local oldFontGpu = opengpu.bind("old-address")
local fontOk, fontErr = pcall(function() oldFontGpu:setFont("unscii8") end)
check(not fontOk, "setFont on a component predating it raises rather than calling nil")
check(not fontOk and tostring(fontErr):lower():find("reboot") ~= nil,
      "and the message says to reboot the computer", tostring(fontErr))

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
local backCanvas = gpu:canvas(32, 32)
local beforeHidden = #calls
local back = gpu:show(backCanvas, { visible = false })
check(calls[#calls].name == "setNodeVisible" and calls[#calls].args[2] == false,
      "show{visible = false} hides the node immediately, so the documented back-buffer setup "
      .. "does not flash an empty buffer over the real frame")
check(#calls == beforeHidden + 2, "and it costs exactly the create plus the hide",
      tostring(#calls - beforeHidden))

local before = #calls
check(front:swapWith(back) == back, "swapWith returns the node now on screen, so it chains")
check(#calls == before + 1, "and it is ONE call, not two setVisible -- two could land in "
      .. "different batches, which is a frame of both-hidden or both-shown",
      (#calls - before) .. " calls")
check(calls[#calls].name == "swapVisibility", "it calls swapVisibility")
check(calls[#calls].args[1] == front.id and calls[#calls].args[2] == back.id,
      "with hide-then-show argument order")
check(calls[#calls].args[3] == 12345,
      "and the scene epoch, so the SERVER can reject ids cached across a re-creation -- the "
      .. "library's own checkAlive cannot, since it compares against a gpu.epoch read at bind()",
      tostring(calls[#calls].args[3]))

check(not pcall(function() front:swapWith(front) end), "swapping a node with itself is refused")
check(not pcall(function() front:swapWith(nil) end), "swapWith(nil) is refused")
check(not pcall(function() front:swapWith({ id = 7 }) end),
      "swapWith refuses a table that is not a node")

-- THE one that was missing, and the reason three reviewers found the same defect. A Canvas has
-- kind = "canvas" and an id of its own, so a duck-type check on those two fields accepts it --
-- and its RESOURCE id then goes to swapVisibility as a NODE id. Separate id spaces, both small
-- integers, so they collide: "Unknown node", or worse, the wrong node revealed with a success
-- return. A `{ id = 7 }` literal never caught this because it has no `kind`.
local strayCanvas = gpu:canvas(8, 8)
check(strayCanvas.id ~= front.id and strayCanvas.id ~= back.id,
      "precondition: the stub gives resources and nodes DIFFERENT ids, so a mix-up is detectable",
      "canvas " .. tostring(strayCanvas.id) .. " vs nodes " .. tostring(front.id) .. "/"
      .. tostring(back.id))
local okCanvas, errCanvas = pcall(function() front:swapWith(strayCanvas) end)
check(not okCanvas, "swapWith refuses a CANVAS handle where a node is required")
check(okCanvas or tostring(errCanvas):find("canvas", 1, true) ~= nil,
      "and the message names what was passed, not just that it was wrong", errCanvas)
strayCanvas:free()

local strayGpu = opengpu.bind("old-address")
local stray = strayGpu:show(strayGpu:canvas(16, 16))
check(not pcall(function() front:swapWith(stray) end),
      "swapWith refuses a node belonging to a DIFFERENT gpu -- node ids are scene-scoped, so "
      .. "the ids would collide silently and reveal the wrong node")
stray:free()

-- checkAlive(OTHER) had no coverage: deleting it left the whole suite green, because every
-- existing case freed `self` rather than the argument. Free the target only.
local liveOne = gpu:show(gpu:canvas(16, 16))
local deadOne = gpu:show(gpu:canvas(16, 16), { visible = false })
deadOne:free()
check(not pcall(function() liveOne:swapWith(deadOne) end),
      "swapWith refuses a freed TARGET, not just a freed self")
liveOne:free()

back:free()
front:free()
check(not pcall(function() front:swapWith(back) end), "a freed node cannot be swapped")

-- An older component must still work, on the fallbacks.
local old = opengpu.bind("old-address")
check(old:limits().submitBytes == 65536, "a component without getLimits falls back, not fails")
check(old:version() == nil, "version() is nil rather than fabricated when unsupported")

local oldA = old:show(old:canvas(8, 8))
local oldB = old:show(old:canvas(8, 8))
local okOld, errOld = pcall(function() oldA:swapWith(oldB) end)
check(not okOld, "swapWith on a component predating it raises rather than calling nil")
check(okOld or tostring(errOld):find("REBOOT", 1, true) ~= nil,
      "and the message says to reboot the computer -- OpenOS caches the proxy, so a client "
      .. "restart does not pick up a new callback", errOld)

print(string.format("=== %d passed, %d failed ===", pass, fail))
os.exit(fail == 0 and 0 or 1)
