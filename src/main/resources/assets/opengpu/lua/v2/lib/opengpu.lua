--[[
  opengpu -- object wrappers over the OpenGPU component.

  The raw callback surface is 58 functions with several edges that are easy to fall off and
  hard to debug. This library exists to remove exactly those, and each wrapper below earns its
  place against one of them:

    * Failures arrive as `nil, message`, NOT as raised errors, so `pcall` does not catch them
      and an unchecked call fails silently. Everything here raises.
    * `canvasSubmit` takes a packed byte string -- big-endian IEEE-754 doubles and a Java
      modified-UTF-8 string for drawText. No program should hand-roll that.
    * Op ids are wire internals discoverable only through `canvasOps()`. Hardcoding them goes
      silently wrong the first time an op is inserted rather than appended.
    * Node ids are integers that persist INTO THE SAVE. A program that dies holding them
      orphans nodes permanently, so handles here know whether they are still alive.
    * The per-tick submit allowance is back-pressure (`false`), not an error (`nil`). Those two
      returns mean opposite things and want opposite handling.

  Lua 5.2 and 5.3+ are both supported: 5.3 has string.pack, 5.2 has math.frexp, and the double
  packer below picks whichever exists.

  Usage:

    local opengpu = require("opengpu")
    local gpu = opengpu.bind()          -- primary component, nodes cleared

    local c = gpu:canvas(160, 120)      -- offscreen canvas
    c:setColor(200, 40, 40):fillRect(0, 0, 160, 120)
    c:setColor(255, 255, 255):text(8, 8, "hello")
    c:publish()

    local n = gpu:show(c)               -- composite it above the display canvas
    n:moveTo(100, 60)
]]

local component = require("component")
local computer = require("computer")

local opengpu = {}

-- ---------------------------------------------------------------------------
-- Calling convention

--[[
  Raise on failure.

  The distinction that matters: a callback that FAILED returns exactly `(nil, message)`, while
  one that simply has no return value returns nothing at all. Testing `result == nil` alone
  would turn every `free()` into an error, so the arity is what separates them.

  `false` is never a failure here -- `setResolution` returns it for a no-op and `canvasSubmit`
  for back-pressure, both of which callers are meant to handle rather than crash on.
]]
local function call(what, fn, ...)
  local r = table.pack(fn(...))
  if r.n >= 1 and r[1] == nil then
    error(what .. " failed: " .. tostring(r[2]), 3)
  end
  return table.unpack(r, 1, r.n)
end

local function checkNumber(name, v)
  if type(v) ~= "number" then
    error(name .. " must be a number, got " .. type(v), 3)
  end
  if v ~= v or v == math.huge or v == -math.huge then
    -- The server refuses these too, but catching them here names the argument. A NaN that got
    -- through would converge identically on every client, so nothing downstream can detect it.
    error(name .. " must be finite", 3)
  end
  return v
end

-- ---------------------------------------------------------------------------
-- Packing

-- 5.3+ has string.pack; 5.2 has math.frexp (which 5.4 removed). One of the two always exists.
local packDouble
if string.pack then
  packDouble = function(v) return string.pack(">d", v) end
else
  packDouble = function(v)
    local sign = 0
    if v < 0 or (v == 0 and 1 / v < 0) then sign, v = 1, -v end
    local mantissa, exponent = 0, 0
    if v == math.huge then
      exponent = 2047
    elseif v ~= 0 then
      local m, e = math.frexp(v)
      exponent = e + 1022
      if exponent < 1 then
        -- Subnormal: the IEEE exponent field is 0 and the stored fraction is v * 2^1074.
        -- frexp gives v = m * 2^e, so m * 2^exponent == v * 2^1022 and the * 2^52 below
        -- completes it. Using (exponent - 1) here encoded every subnormal at HALF its value —
        -- a divergence between the 5.2 and 5.3 paths that only a subnormal input could show.
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
end

local function packInt32(v)
  return string.char(math.floor(v / 16777216) % 256, math.floor(v / 65536) % 256,
                     math.floor(v / 256) % 256, v % 256)
end

--[[
  Encode a string the way DataInput.readUTF expects: a uint16 byte length then modified UTF-8.

  Standard UTF-8 and Java's modified UTF-8 agree for every codepoint from U+0001 to U+FFFF, so
  a normal Lua string passes through untouched. They differ in exactly two places, both of
  which are refused rather than silently mangled:
    * embedded NUL, which modified UTF-8 writes as C0 80 rather than 00;
    * astral characters, which it writes as surrogate PAIRS rather than one 4-byte sequence.
  Neither can render anyway -- the font atlas holds 256 glyphs.
]]
--[[
  Structural caps. FETCHED from getLimits() at bind() time; the values here are only a fallback
  for a component too old to answer it.

  A library that hardcodes a server constant is wrong the first time the constant moves, and
  submitBytes moved: it used to be the per-call ceiling AND the per-tick allowance AND the
  per-batch bound at once, which made every frame over 64 KiB undeliverable. Separating them is
  what fixed it, so keeping `submitBytes` and `submitBytesPerTick` distinct here is load-bearing
  rather than tidy -- a caller CHUNKS by the first and PACES by the second.
]]
local FALLBACK_LIMITS = {
  submitBytes = 65536,          -- V2Wire.MAX_SUBMIT_BYTES
  submitBytesPerTick = 131072,  -- V2Wire.MAX_SUBMIT_BYTES_PER_TICK
  commandCap = 4096,            -- TileEntityGpu2.CANVAS_COMMAND_CAP
  textChars = 8192,             -- V2Wire.MAX_TEXT_CHARS
}

-- One BMP scalar value as a 3-byte UTF-8 sequence. Arithmetic rather than bitwise operators
-- on purpose: those are Lua 5.3+ syntax and this library is meant to load on every
-- architecture OpenComputers offers.
local function utf8Three(v)
  return string.char(0xE0 + math.floor(v / 4096),
                     0x80 + math.floor(v / 64) % 64,
                     0x80 + v % 64)
end

local function packUTF(s, maxChars)
  -- Walk the string as UTF-8, validating as we go and counting CHARACTERS -- the server's cap
  -- is in characters while the wire length is in bytes, and the two differ for anything above
  -- ASCII. Validating matters more than it looks: readUTF answers a malformed sequence by
  -- throwing, which fails the WHOLE submit, so one bad byte in one label would discard an
  -- entire frame with an error naming none of it.
  --
  -- ASTRAL CHARACTERS ARE RE-ENCODED, NOT REFUSED. The server decodes with readUTF, which
  -- speaks Java's MODIFIED UTF-8: a codepoint above the BMP is written as a surrogate PAIR of
  -- two 3-byte sequences (6 bytes), never as one 4-byte sequence. Standard UTF-8 -- which is
  -- what a Lua string holds -- uses the 4-byte form, so passing it through unchanged would
  -- make readUTF throw and lose the whole frame. This converts.
  --
  -- An earlier version simply refused, on the grounds that the 256-glyph atlas could not draw
  -- one anyway. That second reason expired when text moved to Unifont, which carries astral
  -- glyphs; the first was never a reason to refuse, only work to do.
  local chars, i, n = 0, 1, #s
  local out, plain = nil, 1  -- `out` is built only if something actually needs transforming
  while i <= n do
    local b = s:byte(i)
    local len
    if b == 0 then
      error("drawText: embedded NUL is not encodable in modified UTF-8", 4)
    elseif b < 0x80 then
      len = 1
    elseif b < 0xC2 then
      error("drawText: malformed UTF-8 at byte " .. i .. " (stray continuation byte)", 4)
    elseif b < 0xE0 then
      len = 2
    elseif b < 0xF0 then
      len = 3
    elseif b < 0xF5 then
      len = 4
    else
      error("drawText: malformed UTF-8 at byte " .. i .. " (invalid lead byte)", 4)
    end
    for k = 1, len - 1 do
      local c = s:byte(i + k)
      if not c or c < 0x80 or c > 0xBF then
        error("drawText: malformed UTF-8 at byte " .. i, 4)
      end
    end
    if len == 4 then
      local b1, b2, b3, b4 = s:byte(i, i + 3)
      local cp = (b1 - 0xF0) * 0x40000 + (b2 - 0x80) * 0x1000
               + (b3 - 0x80) * 0x40 + (b4 - 0x80)
      -- Reject overlongs and anything past the last real codepoint before it reaches the
      -- surrogate arithmetic, which would otherwise produce a garbage pair.
      if cp < 0x10000 or cp > 0x10FFFF then
        error("drawText: malformed UTF-8 at byte " .. i .. " (codepoint out of range)", 4)
      end
      out = out or {}
      if plain < i then
        out[#out + 1] = s:sub(plain, i - 1)
      end
      local u = cp - 0x10000
      out[#out + 1] = utf8Three(0xD800 + math.floor(u / 0x400))
                   .. utf8Three(0xDC00 + u % 0x400)
      plain = i + 4
      -- TWO characters, because that is what the server counts: a Java String holds an astral
      -- codepoint as a surrogate pair, and the cap it checks is String.length().
      chars = chars + 2
    else
      chars = chars + 1
    end
    i = i + len
  end
  maxChars = maxChars or FALLBACK_LIMITS.textChars
  if chars > maxChars then
    error("drawText: string too long (" .. chars .. " characters, max " .. maxChars .. ")", 4)
  end
  local body = s
  if out then
    if plain <= n then
      out[#out + 1] = s:sub(plain)
    end
    body = table.concat(out)
  end
  -- Length prefix is the byte count of what actually goes on the wire, which is longer than
  -- the input whenever an astral character was expanded.
  local blen = #body
  return string.char(math.floor(blen / 256), blen % 256) .. body
end

-- ---------------------------------------------------------------------------
-- Command buffer

--[[
  The buffer chunks by `limits.submitBytes` -- the PER-CALL ceiling, which is what one
  canvasSubmit may carry.

  Genuinely distinct from `limits.submitBytesPerTick`, and they fail differently: over the
  per-call ceiling the callback THROWS, so there is nothing to wait for; over the per-tick
  allowance it returns the retryable `false`. It is also tighter than it looks -- a command costs
  1 + 8*argc bytes, so a setColor+filledRectangle pair is 66 bytes and only ~992 pairs fit. A
  canvas at the default command cap therefore cannot send a full frame in one call, which is why
  submit() chunks at all.
]]
local Buffer = {}
Buffer.__index = Buffer

local function newBuffer(ops, limits)
  return setmetatable({
    ops = ops, limits = limits or FALLBACK_LIMITS,
    parts = {}, sizes = {}, n = 0, size = 0,
  }, Buffer)
end

function Buffer:reset()
  self.parts = {}
  self.sizes = {}
  self.n = 0
  self.size = 0
  return self
end

function Buffer:isEmpty()
  return self.n == 0
end

--[[
  Split the buffer into pieces each of which fits one submit.

  Chunking is safe because canvas commands replay in order and `append` compacts exactly as the
  immediate path does: sending [1..k] then [k+1..n] leaves the same visible list as sending all
  n at once. Splitting a PUBLISH means the first piece publishes (replacing the frame) and the
  rest append to it, which is why the caller must send them in order and without interruption.
]]
function Buffer:chunks()
  local out, start, bytes = {}, 1, 0
  for i = 1, self.n do
    local sz = self.sizes[i]
    if bytes + sz > self.limits.submitBytes - 4 and i > start then
      out[#out + 1] = { from = start, to = i - 1 }
      start, bytes = i, 0
    end
    bytes = bytes + sz
  end
  if start <= self.n then
    out[#out + 1] = { from = start, to = self.n }
  end
  return out
end

function Buffer:chunkBytes(chunk)
  local n = chunk.to - chunk.from + 1
  return packInt32(n) .. table.concat(self.parts, "", chunk.from, chunk.to)
end

--[[
  Append one command.

  Arity comes from canvasOps() rather than a table in this file, so inserting an op server-side
  cannot silently shift every id underneath a program.
]]
function Buffer:op(name, args, text)
  local spec = self.ops[name]
  if not spec then
    error("unknown canvas op '" .. tostring(name) .. "'", 3)
  end
  if #args ~= spec.args then
    error(name .. " takes " .. spec.args .. " numbers, got " .. #args, 3)
  end
  local out = { string.char(spec.op) }
  for i = 1, #args do
    out[#out + 1] = packDouble(checkNumber(name .. " arg " .. i, args[i]))
  end
  if text ~= nil then
    out[#out + 1] = packUTF(text, self.limits.textChars)
  end
  local part = table.concat(out)
  if #part > self.limits.submitBytes - 4 then
    -- A single command too big to ever send. Only drawText can reach this, and only with a
    -- string longer than any font could render; refuse it here so it cannot wedge the buffer.
    error(name .. " encodes to " .. #part .. " bytes, more than one submit can carry", 3)
  end
  self.parts[#self.parts + 1] = part
  self.sizes[#self.sizes + 1] = #part
  self.n = self.n + 1
  self.size = self.size + #part
  return self
end

--- Encoded size of the pending frame, in bytes. Compare against gpu:limits() to see whether it
--- fits one call (submitBytes) and whether it fits one tick (submitBytesPerTick).
function Buffer:byteSize()
  return self.size + 4
end

-- ---------------------------------------------------------------------------
-- Node handle

-- Forward declaration. Canvas is defined further down, but Node:swapWith needs to name it to tell
-- a caller who passed a canvas what they actually passed. Without this the identifier resolves to
-- a GLOBAL inside every Node method -- nil, silently, so the comparison is simply never true and
-- the branch is dead code that still reads like it works.
local Canvas

local Node = {}
Node.__index = Node

local function checkAlive(self, what)
  if not self.valid then
    error(what .. " on a freed " .. self.kind .. " (id " .. tostring(self.id) .. ")", 3)
  end
  if self.gpu.epoch ~= self.epoch then
    -- The scene was re-created underneath this handle. Its id may now belong to something
    -- else entirely, so using it would draw into a stranger's resource rather than fail.
    error(self.kind .. " handle is stale: the scene was re-created", 3)
  end
end

function Node:moveTo(x, y, opts)
  checkAlive(self, "moveTo")
  opts = opts or {}
  call("setNodeTransform", self.gpu.raw.setNodeTransform, self.id,
       checkNumber("x", x), checkNumber("y", y),
       opts.rotation or 0, opts.scaleX or 1, opts.scaleY or 1, opts.teleport and true or false)
  return self
end

function Node:setZ(z)
  checkAlive(self, "setZ")
  call("setNodeZ", self.gpu.raw.setNodeZ, self.id, checkNumber("z", z))
  return self
end

function Node:setVisible(visible)
  checkAlive(self, "setVisible")
  call("setNodeVisible", self.gpu.raw.setNodeVisible, self.id, visible and true or false)
  return self
end

--[[
  Hide this node and show `other`, indivisibly -- the double-buffer swap.

  Why it exists. A frame too big for one submit is sent as several calls, and the server seals a
  batch on a tick boundary that can fall BETWEEN them. Publish-then-append across that boundary
  means watchers render the first chunk alone: a half-drawn frame. Widening the byte allowance
  made that rare, not impossible, because batch membership is about timing rather than size.

  So do not compose a frame where anyone can see it. Draw into a hidden node over as many calls
  and ticks as it takes, then swap:

      local front, back = gpu:show(a), gpu:show(b)
      back:setVisible(false)
      -- ... any number of back-buffer publishes, across any number of ticks ...
      front:swapWith(back)      -- the viewer sees the old frame, then the new one. Never both,
                                -- never half of either.

  Two setVisible calls do NOT do this: they are separate deltas that can land in separate
  batches, which is one frame of both-hidden or both-shown.

  Refuses swapping a node with itself -- that would be hide-then-show on one node, a no-op that
  still spends two deltas and would read as if it had worked.
]]
function Node:swapWith(other)
  checkAlive(self, "swapWith")
  if self.gpu.raw.swapVisibility == nil then
    error("this opengpu component predates swapVisibility; update the jar and REBOOT the "
          .. "computer (OpenOS caches the component proxy, so a client restart is not enough)", 2)
  end
  --[[
    Identity by METATABLE, not by duck-typing the fields.

    The first version tested `other.kind ~= nil and other.id ~= nil`, which a CANVAS satisfies:
    canvases carry kind = "canvas" and an id of their own. So a canvas handle passed here sailed
    through and its RESOURCE id was handed to swapVisibility as a NODE id. Those are separate id
    spaces, both small integers starting near 1, so they collide constantly -- the call either
    raised a confusing "Unknown node" or, when a node happened to hold that number, silently
    revealed the WRONG node while returning success.

    Nor does `kind` disambiguate on its own: a canvas is "canvas" and a canvas NODE is
    "canvas node", one substring apart. The metatable is the only thing that actually says what
    this object is.
  ]]
  if getmetatable(other) ~= Node then
    local what = type(other)
    if what == "table" and getmetatable(other) == Canvas then
      what = "a canvas -- pass the NODE showing it (from gpu:show(canvas)), not the canvas"
    end
    error("swapWith needs another node, got " .. what, 2)
  end
  checkAlive(other, "swapWith")
  -- By ADDRESS, not by table identity. Two bind() calls to the same GPU produce two different
  -- Gpu tables whose nodes live in one scene and swap perfectly well; comparing the wrappers
  -- would refuse that with a message blaming the wrong thing. The address is the device.
  if other.gpu.address ~= self.gpu.address then
    error("swapWith needs two nodes on the SAME gpu; node ids are scene-scoped, so an id from "
          .. "another screen's scene would name a different node here, not an absent one", 2)
  end
  if other.id == self.id then
    error("swapWith needs two different nodes", 2)
  end
  -- The epoch goes to the SERVER because checkAlive above cannot answer this question. It
  -- compares self.epoch against self.gpu.epoch, and gpu.epoch was read once at bind() -- so if
  -- the scene is re-created AFTER binding, both are equally stale and the comparison passes.
  -- Only the server knows the live epoch. Without this backstop a program holding node ids
  -- across a re-creation swaps two strangers: the ids are valid, just no longer the ones it
  -- meant, and server and every mirror agree on the wrong answer.
  call("swapVisibility", self.gpu.raw.swapVisibility, self.id, other.id, self.epoch)
  return other
end

--[[
  Tint multiplies the node's output -- but ONLY on sprite nodes.

  The renderer resets colour per node and reads the tint in the sprite path alone, so tinting a
  canvas node converges perfectly on both sides and changes nothing on screen. Refusing here
  turns an invisible no-op into a clear error; remove this guard if the renderer ever grows
  canvas tinting.
]]
function Node:setTint(r, g, b, a)
  checkAlive(self, "setTint")
  if self.kind ~= "sprite" then
    error("setTint has no effect on a " .. self.kind .. " node; only sprites are tinted", 2)
  end
  call("setNodeTint", self.gpu.raw.setNodeTint, self.id, r, g, b, a or 255)
  return self
end

--[[
  Release the node.

  Idempotent, and it will NOT free across a scene re-creation. Every other method raises on a
  stale handle; free() must instead drop it quietly, because the id it holds may now belong to
  a node the new scene created, and freeing that would destroy a stranger's work while looking
  like it succeeded. Raising would be no better -- cleanup code runs in teardown paths where an
  error is worse than a no-op.
]]
function Node:free()
  if not self.valid then return end
  self.valid = false
  self.gpu.nodes[self.id] = nil
  if self.gpu.epoch ~= self.epoch then
    return -- the scene this id belonged to is gone; the server already dropped it
  end
  call("freeNode", self.gpu.raw.freeNode, self.id)
end

-- ---------------------------------------------------------------------------
-- Canvas handle

Canvas = {}   -- forward-declared above, so Node methods can name it
Canvas.__index = Canvas

--[[
  Drawing accumulates into a local buffer and is sent by publish()/append().

  Nothing reaches the server until then, which is the point: the immediate-mode callbacks are
  hardwired to the display canvas and REFUSE an offscreen one, so a whole finished command list
  is the only way in. It is also the cheaper way -- one call instead of one per primitive.
]]
function Canvas:setColor(r, g, b, a)
  checkAlive(self, "setColor")
  self.buffer:op("setColor", { r, g, b, a or 255 })
  return self
end

function Canvas:fill()
  checkAlive(self, "fill")
  self.buffer:op("fill", {})
  return self
end

-- Wire font ids. Kept here rather than fetched because they are part of the op arguments, not
-- the op table -- canvasOps tells us the op id, not what its argument means.
local FONTS = { unifont = 0, default = 0, unscii8 = 1, ["unscii-8"] = 1 }

--[[
  Select the font for subsequent text on this canvas.

  Ambient state with exactly setColor's lifecycle: it applies until changed, is NOT saved by
  push/pop, and resets to unifont at the start of every canvas replay. Do not assume a font
  carries over from a previous frame -- publish() replaces the command list, so the reset is
  what you start from.

  The canvas remembers the selection so that Canvas:textWidth measures with the font this
  canvas will actually draw with. That is the whole reason to track it in Lua: the server's
  getTextWidth takes an explicit font, and a program measuring with the default while drawing
  in unscii would silently mis-lay-out every line.
]]
function Canvas:setFont(name)
  checkAlive(self, "setFont")
  local id = FONTS[name]
  if not id then
    error("unknown font '" .. tostring(name) .. "' (unifont, unscii8)", 3)
  end
  self.buffer:op("setFont", { id })
  self.font = id
  return self
end

--[[
  Width of `str` in this canvas's current font, in pixels.

  Prefer this over gpu:textWidth for anything you are about to draw on this canvas: it cannot
  disagree with what setFont selected, whereas the gpu-level call defaults to unifont whatever
  the canvas is using.
]]
function Canvas:textWidth(str)
  checkAlive(self, "textWidth")
  return self.gpu:textWidth(str, self.font or 0)
end

function Canvas:plot(x, y) checkAlive(self, "plot") self.buffer:op("plot", { x, y }) return self end

function Canvas:line(x1, y1, x2, y2)
  checkAlive(self, "line")
  self.buffer:op("line", { x1, y1, x2, y2 })
  return self
end

function Canvas:rect(x, y, w, h)
  checkAlive(self, "rect")
  self.buffer:op("rectangle", { x, y, w, h })
  return self
end

function Canvas:fillRect(x, y, w, h)
  checkAlive(self, "fillRect")
  self.buffer:op("filledRectangle", { x, y, w, h })
  return self
end

function Canvas:clearRect(x, y, w, h)
  checkAlive(self, "clearRect")
  self.buffer:op("clearRectangle", { x, y, w, h })
  return self
end

function Canvas:oval(cx, cy, w, h)
  checkAlive(self, "oval")
  self.buffer:op("oval", { cx, cy, w, h })
  return self
end

function Canvas:fillOval(cx, cy, w, h)
  checkAlive(self, "fillOval")
  self.buffer:op("filledOval", { cx, cy, w, h })
  return self
end

function Canvas:triangle(x1, y1, x2, y2, x3, y3)
  checkAlive(self, "triangle")
  self.buffer:op("triangle", { x1, y1, x2, y2, x3, y3 })
  return self
end

function Canvas:fillTriangle(x1, y1, x2, y2, x3, y3)
  checkAlive(self, "fillTriangle")
  self.buffer:op("filledTriangle", { x1, y1, x2, y2, x3, y3 })
  return self
end

function Canvas:text(x, y, str)
  checkAlive(self, "text")
  if type(str) ~= "string" then
    error("text expects a string, got " .. type(str), 2)
  end
  self.buffer:op("drawText", { x, y }, str)
  return self
end

--[[
  Draw a texture by id.

  Takes a NUMBER, not a handle. There is no texture wrapper in this library yet, so the only
  tables carrying an `.id` are canvases and nodes — exactly the two things drawTexture must
  refuse. Unwrapping `.id` from any table would have turned "you passed the wrong object" into
  a silent reference to an unrelated resource.
]]
function Canvas:drawTexture(textureId, x, y)
  checkAlive(self, "drawTexture")
  if type(textureId) ~= "number" then
    error("drawTexture expects a texture id (a number), got " .. type(textureId)
          .. "; a canvas is displayed with gpu:show(), not drawn as a texture", 2)
  end
  self.buffer:op("drawTexture", { textureId, x, y })
  return self
end

function Canvas:push() checkAlive(self, "push") self.buffer:op("push", {}) return self end
function Canvas:pop() checkAlive(self, "pop") self.buffer:op("pop", {}) return self end
function Canvas:origin() checkAlive(self, "origin") self.buffer:op("origin", {}) return self end

function Canvas:translate(dx, dy)
  checkAlive(self, "translate")
  self.buffer:op("translate", { dx, dy })
  return self
end

function Canvas:rotate(angle)
  checkAlive(self, "rotate")
  self.buffer:op("rotate", { angle })
  return self
end

function Canvas:scale(sx, sy)
  checkAlive(self, "scale")
  self.buffer:op("scale", { sx, sy })
  return self
end

--[[
  Send the buffer.

  Back-pressure is retried rather than raised. The per-tick allowance is shared by every
  computer on this GPU, so `false` means "someone got there first this tick" -- a normal
  condition on a busy scene, not a fault. Retrying costs a tick; failing would cost the frame.
]]
--[[
  Send the buffer, splitting it across as many submits as it needs.

  Two failure modes are deliberately handled differently, because they are not alike:

  * OVER THE PER-CALL BYTE CEILING -- not handled, PREVENTED. A frame larger than one submit is
    chunked: the first piece carries `mode`, the rest append. That is safe (commands replay in
    order and append compacts identically) and it is what makes the canvas command cap usable
    at all, since 4096 commands of most ops encode to well over 64 KiB.
  * OVER THE PER-TICK ALLOWANCE -- reported, not retried by default. The allowance is shared by
    every computer on this GPU, so `false` means somebody else got there first.

  Why retry is opt-in rather than automatic: waiting means os.sleep, and os.sleep DISCARDS every
  signal that arrives while it runs. For an interactive program -- anything handling touch, key
  or timer events -- a helpful-looking retry would silently eat the user's input. A program that
  does not care can ask for it with `c:publish{ retry = true }`.

  On a partial failure mid-chunk the buffer is KEPT, not reset, so the caller still holds the
  frame. Note the canvas is then in a torn state: earlier chunks have applied. Re-publishing is
  the recovery, which is why publish() is the safe default for whole frames.
]]
local function submit(self, mode, opts)
  checkAlive(self, mode)
  opts = opts or {}
  if self.buffer:isEmpty() then
    return false, "nothing to " .. mode
  end

  local chunks = self.buffer:chunks()
  for index, chunk in ipairs(chunks) do
    -- Only the FIRST chunk carries the caller's mode. A publish replaces the frame, so a
    -- second publish would throw away the piece just sent.
    local chunkMode = (index == 1) and mode or "append"
    local payload = self.buffer:chunkBytes(chunk)
    local deadline = opts.retry and (computer.uptime() + (opts.timeout or 2)) or nil

    while true do
      local ok, msg = self.gpu.raw.canvasSubmit(self.id, chunkMode, payload, self.epoch)
      if ok == nil then
        error("canvasSubmit failed: " .. tostring(msg), 3)
      elseif ok then
        break
      end
      -- ok == false: this tick's allowance is spent.
      if not deadline or computer.uptime() > deadline then
        return false, msg, index
      end
      os.sleep(0.05)
    end
  end

  self.buffer:reset()
  return true
end

function Canvas:publish(opts) return submit(self, "publish", opts) end
function Canvas:append(opts) return submit(self, "append", opts) end

--- Encoded size of the pending frame in bytes, and how many submits it will take.
function Canvas:pending()
  return self.buffer:byteSize(), #self.buffer:chunks()
end

function Canvas:discard()
  self.buffer:reset()
  return self
end

function Canvas:size()
  checkAlive(self, "size")
  return self.width, self.height
end

--- Release the canvas. Same stale-handle rule as Node:free().
function Canvas:free()
  if not self.valid then return end
  self.valid = false
  self.gpu.canvases[self.id] = nil
  if self.gpu.epoch ~= self.epoch then
    return
  end
  call("freeCanvas", self.gpu.raw.freeCanvas, self.id)
end

-- ---------------------------------------------------------------------------
-- GPU

local Gpu = {}
Gpu.__index = Gpu

function Gpu:size() return call("getSize", self.raw.getSize) end
function Gpu:resolution() return call("getResolution", self.raw.getResolution) end
function Gpu:maxResolution() return call("maxResolution", self.raw.maxResolution) end

--[[
  A resolution change DISCARDS the display canvas by contract, and is rate-limited server-side.
  Every canvas and node handle survives it -- they are separate resources -- but anything the
  program had drawn immediately is gone.
]]
function Gpu:setResolution(w, h)
  return call("setResolution", self.raw.setResolution, checkNumber("width", w),
              checkNumber("height", h))
end

--- Immediate drawing, always on the display canvas. Offscreen canvases use Canvas methods.
function Gpu:setColor(r, g, b, a)
  call("setColor", self.raw.setColor, r, g, b, a or 255)
  return self
end

function Gpu:clear() call("clear", self.raw.clear) return self end
function Gpu:fill() call("fill", self.raw.fill) return self end
function Gpu:present() call("present", self.raw.present) return self end

function Gpu:fillRect(x, y, w, h)
  call("filledRectangle", self.raw.filledRectangle, x, y, w, h)
  return self
end

function Gpu:text(x, y, str)
  call("drawText", self.raw.drawText, str, x, y)
  return self
end

--[[
  Width of `str` in pixels, measured with `font` (a name or wire id; default unifont).

  The font is EXPLICIT rather than a "current font" on the gpu, deliberately. Drawing goes
  through a canvas's command stream while this call does not, so a gpu-level current font
  could disagree with what a canvas actually draws with and nothing would reconcile them.
  When measuring text you are about to draw on a canvas, prefer Canvas:textWidth, which uses
  that canvas's own font.
]]
function Gpu:textWidth(str, font)
  local id = font
  if id == nil then
    id = 0
  elseif type(id) == "string" then
    id = FONTS[id] or error("unknown font '" .. id .. "' (unifont, unscii8)", 2)
  end
  return call("getTextWidth", self.raw.getTextWidth, str, id)
end

--[[
  Cell width and height in pixels for a font: 8x16 for unifont, 8x8 for unscii8.

  The height is the line pitch -- stack rows by it rather than by a constant, because it
  differs per font and hardcoding 16 silently overlaps every unscii line by 8 pixels.
]]
function Gpu:fontMetrics(font)
  local id = font
  if id == nil then
    id = 0
  elseif type(id) == "string" then
    id = FONTS[id] or error("unknown font '" .. id .. "' (unifont, unscii8)", 2)
  end
  return call("getFontMetrics", self.raw.getFontMetrics, id)
end

--- An offscreen canvas. Draw into it with Canvas methods, then publish().
function Gpu:canvas(width, height, commandCap)
  local id
  if commandCap then
    id = call("createCanvas", self.raw.createCanvas, width, height, commandCap)
  else
    id = call("createCanvas", self.raw.createCanvas, width, height)
  end
  local c = setmetatable({
    gpu = self, id = id, kind = "canvas", valid = true, epoch = self.epoch,
    width = width, height = height, buffer = newBuffer(self.ops, self.lim),
  }, Canvas)
  self.canvases[id] = c
  return c
end

--- Composite a canvas above the display canvas as a node.
--[[
  Show a canvas as a node. `opts.visible = false` creates it hidden.

  That option exists to close a race in the double-buffer setup this library recommends. Writing
  `local back = gpu:show(b); back:setVisible(false)` is TWO calls, and the node is created
  visible: if a batch seals between them -- which it may, since they are separate direct calls and
  the seal runs on the server thread -- viewers get one frame of an empty back buffer over the
  real one. That is a smaller version of exactly the tear swapWith exists to prevent, in the code
  the documentation tells people to write.

  A hidden node still costs the createCanvasNode call; there is no way to make node creation and
  the hide one delta without a server-side change, so this issues setNodeVisible immediately and
  accepts a one-call window in which nothing has been DRAWN into the canvas yet. An empty canvas
  renders nothing, so the window is invisible in practice -- unlike the version where the canvas
  already holds the previous frame.
]]
function Gpu:show(canvas, opts)
  if getmetatable(canvas) ~= Canvas then
    error("show expects a canvas created by gpu:canvas()", 2)
  end
  checkAlive(canvas, "show")
  local id = call("createCanvasNode", self.raw.createCanvasNode, canvas.id)
  local n = setmetatable({
    gpu = self, id = id, kind = "canvas node", valid = true, epoch = self.epoch,
  }, Node)
  self.nodes[id] = n
  if opts and opts.visible == false then
    n:setVisible(false)
  end
  return n
end

--- A sprite node over a texture id. Sprites are the only nodes tint applies to.
function Gpu:sprite(textureId)
  local id = call("createSprite", self.raw.createSprite, textureId)
  local n = setmetatable({
    gpu = self, id = id, kind = "sprite", valid = true, epoch = self.epoch,
  }, Node)
  self.nodes[id] = n
  return n
end

--[[
  Free every node but the display node.

  Nodes are retained AND persisted: they outlive the program, survive a reboot and are written
  into the world save. A program that starts without this inherits whatever the last one left
  behind, and one that dies holding ids orphans them permanently. bind() calls it by default
  for that reason.
]]
function Gpu:clearNodes()
  for _, n in pairs(self.nodes) do n.valid = false end
  self.nodes = {}
  return call("clearNodes", self.raw.clearNodes)
end

--[[
  The structural caps this GPU enforces, as a table. Read at bind() and never re-read.

  Chunk by `submitBytes`, pace by `submitBytesPerTick`. They are different numbers answering
  different questions, and treating them as one is what made frames over 64 KiB undeliverable.
  A frame larger than submitBytesPerTick still publishes, but spans ticks and may show torn for
  one of them; `Canvas:pending()` tells you the size before you send anything.
]]
function Gpu:limits()
  local out = {}
  for k, v in pairs(self.lim) do out[k] = v end
  return out
end

--[[
  Version identity: `{ api = number, protocol = number, mod = string }`, or nil on a component
  too old to answer.

  Branch on `api >= N` for feature detection -- it is monotone and independent of both the wire
  protocol (which a program cannot observe) and the mod version (which moves on releases that
  change nothing callable).
]]
function Gpu:version()
  if not self.ver then return nil end
  local out = {}
  for k, v in pairs(self.ver) do out[k] = v end
  return out
end

--- Bytes still admissible this tick, and the per-tick ceiling they are measured against.
function Gpu:submitBudget() return call("getSubmitBudget", self.raw.getSubmitBudget) end
function Gpu:epochOf() return call("getEpoch", self.raw.getEpoch) end

--- Memory accounting, in bytes.
function Gpu:memory()
  return call("getFreeMemory", self.raw.getFreeMemory),
         call("getTotalMemory", self.raw.getTotalMemory)
end

--[[
  Re-read the scene epoch and invalidate every handle if it moved.

  Worth calling after anything that might have re-created the scene (a long sleep, a chunk
  reload). Handles then raise instead of addressing a recycled id.
]]
function Gpu:refresh()
  local now = self:epochOf()
  if now ~= self.epoch then
    -- Mark the handles dead as well as dropping them. Clearing the tables alone would leave a
    -- caller's own reference still `valid`, relying on the epoch comparison to catch it — and
    -- that comparison is between two Lua-side copies, so it only ever fires because this
    -- method just moved one of them. Setting valid=false makes the handle dead outright.
    for _, c in pairs(self.canvases) do c.valid = false end
    for _, n in pairs(self.nodes) do n.valid = false end
    self.epoch = now
    self.canvases = {}
    self.nodes = {}
    return true
  end
  return false
end

--[[
  Free every canvas this handle created.

  Offscreen canvases are charged against the GPU's VRAM budget and, unlike nodes, there is no
  server call that enumerates them — nothing outside this library knows their ids. A program
  that exits without freeing them leaks that VRAM until the scene itself is re-created, and no
  later program can recover it. Call this on the way out, or use gpu:reset().
]]
function Gpu:clearCanvases()
  local freed = 0
  for _, c in pairs(self.canvases) do
    if c.valid and c.epoch == self.epoch then
      c.valid = false
      call("freeCanvas", self.raw.freeCanvas, c.id)
      freed = freed + 1
    end
    c.valid = false
  end
  self.canvases = {}
  return freed
end

--- Drop everything this handle created: nodes first, then canvases they may have displayed.
function Gpu:reset()
  local nodes = self:clearNodes()
  local canvases = self:clearCanvases()
  return nodes, canvases
end

-- ---------------------------------------------------------------------------
-- Entry point

--[[
  Bind a GPU.

  @param address  optional component address; defaults to the primary `opengpu`.
  @param opts     { keepNodes = true } to inherit whatever the previous program left in the
                  scene instead of clearing it.
]]
function opengpu.bind(address, opts)
  opts = opts or {}
  local raw
  if address then
    -- NOT `component.proxy(address) or component.opengpu`. That reads naturally and is wrong:
    -- proxy() returns nil for an address that does not resolve, so `or` would quietly bind
    -- the PRIMARY gpu instead -- a program aimed at one screen would drive another and look
    -- like a rendering bug rather than a typo.
    local proxy, reason = component.proxy(address)
    if not proxy then
      error("no component at address " .. tostring(address) .. ": " .. tostring(reason), 2)
    end
    if proxy.type ~= "opengpu" then
      error("component " .. tostring(address) .. " is a " .. tostring(proxy.type)
            .. ", not an opengpu", 2)
    end
    raw = proxy
  else
    -- component.opengpu asserts if none is available, so the error below is belt and braces.
    raw = component.opengpu
    if not raw then
      error("no opengpu component available", 2)
    end
  end
  if raw.canvasOps == nil then
    error("this opengpu component predates canvasOps; the mod and this library disagree "
          .. "-- if the jar was just updated, reboot the computer", 2)
  end
  -- Fetched once at bind, not per call: these are structural constants, and re-reading them on
  -- every submit would spend call budget to learn something that cannot have changed.
  --
  -- Tolerant of absence rather than fatal, unlike canvasOps above. Op ids are unguessable, so a
  -- component without canvasOps cannot be driven at all; the limits have known fallbacks, so an
  -- older component stays usable on the values it shipped with. The asymmetry is deliberate.
  local lim = FALLBACK_LIMITS
  if raw.getLimits ~= nil then
    local fetched = call("getLimits", raw.getLimits)
    if type(fetched) == "table" then
      lim = {}
      for k, v in pairs(FALLBACK_LIMITS) do lim[k] = v end
      for k, v in pairs(fetched) do
        if type(v) == "number" and v > 0 then lim[k] = math.floor(v) end
      end
    end
  end

  local gpu = setmetatable({
    raw = raw,
    address = raw.address,
    ops = call("canvasOps", raw.canvasOps),
    epoch = call("getEpoch", raw.getEpoch),
    lim = lim,
    ver = raw.getVersion ~= nil and call("getVersion", raw.getVersion) or nil,
    canvases = {},
    nodes = {},
  }, Gpu)
  if not opts.keepNodes then
    gpu:clearNodes()
  end
  return gpu
end

opengpu.Gpu = Gpu
opengpu.Canvas = Canvas
opengpu.Node = Node

return opengpu
