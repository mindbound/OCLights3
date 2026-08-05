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

local function packUTF(s, maxChars)
  -- Walk the string as UTF-8, validating as we go and counting CHARACTERS -- the server's cap
  -- is in characters while the wire length is in bytes, and the two differ for anything above
  -- ASCII. Validating matters more than it looks: readUTF answers a malformed sequence by
  -- throwing, which fails the WHOLE submit, so one bad byte in one label would discard an
  -- entire frame with an error naming none of it.
  local chars, i, n = 0, 1, #s
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
    else
      -- Astral characters: modified UTF-8 writes a surrogate PAIR rather than one 4-byte
      -- sequence, and the 256-glyph atlas could not render one anyway.
      error("drawText: characters outside the Basic Multilingual Plane are not supported", 4)
    end
    for k = 1, len - 1 do
      local c = s:byte(i + k)
      if not c or c < 0x80 or c > 0xBF then
        error("drawText: malformed UTF-8 at byte " .. i, 4)
      end
    end
    i = i + len
    chars = chars + 1
  end
  maxChars = maxChars or FALLBACK_LIMITS.textChars
  if chars > maxChars then
    error("drawText: string too long (" .. chars .. " characters, max " .. maxChars .. ")", 4)
  end
  return string.char(math.floor(n / 256), n % 256) .. s
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
  if type(other) ~= "table" or other.kind == nil or other.id == nil then
    error("swapWith needs another node, got " .. type(other), 2)
  end
  checkAlive(other, "swapWith")
  if other.gpu ~= self.gpu then
    error("swapWith needs two nodes on the SAME gpu; a node cannot be revealed by another "
          .. "screen's scene", 2)
  end
  if other.id == self.id then
    error("swapWith needs two different nodes", 2)
  end
  call("swapVisibility", self.gpu.raw.swapVisibility, self.id, other.id)
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

local Canvas = {}
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

function Gpu:textWidth(str) return call("getTextWidth", self.raw.getTextWidth, str) end

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
function Gpu:show(canvas)
  if getmetatable(canvas) ~= Canvas then
    error("show expects a canvas created by gpu:canvas()", 2)
  end
  checkAlive(canvas, "show")
  local id = call("createCanvasNode", self.raw.createCanvasNode, canvas.id)
  local n = setmetatable({
    gpu = self, id = id, kind = "canvas node", valid = true, epoch = self.epoch,
  }, Node)
  self.nodes[id] = n
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
