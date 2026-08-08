OpenGPU library disk
====================

Read-only, mounted into any computer connected to an `opengpu` component.

  lib/opengpu.lua   the library
  .autorun.lua      puts lib/ on package.path so require() finds it

Quick start:

  local opengpu = require("opengpu")
  local gpu = opengpu.bind()

  local c = gpu:canvas(160, 120)
  c:setColor(200, 40, 40):fillRect(0, 0, 160, 120)
  c:setColor(255, 255, 255):text(8, 8, "hello")
  c:publish()

  local panel = gpu:show(c)
  panel:moveTo(100, 60)

If require() cannot find it, autorun is probably disabled on this computer. Either
re-enable it (see the OpenOS `filesystem` docs) or add the path yourself:

  package.path = package.path .. ";/mnt/<disk>/lib/?.lua"

Four things the library exists to handle, each of which bites programs that call
the component directly:

  * Component failures return `nil, message` instead of raising, so `pcall` does
    not catch them and an unchecked call fails silently. Every wrapper raises.
  * Offscreen canvases are drawn into by submitting a packed command list --
    big-endian doubles, and a Java modified-UTF-8 string for text. Canvas methods
    build that for you.
  * Nodes persist into the world save. They outlive the program and survive a
    reboot, so bind() clears them unless you pass { keepNodes = true }.
  * The per-tick submit allowance is shared by every computer on the GPU.
    publish() treats exhaustion as back-pressure and retries rather than failing.

This disk carries the library only; user-facing documentation is not written yet.

Until it is, everything is discoverable from inside the game. Every callback carries a
doc string, so OpenOS's own components command prints the whole surface:

  components -l opengpu

and one at a time, if you know the name:

  local component = require("component")
  print(component.doc(component.opengpu.address, "drawText"))

Nothing needs hardcoding -- the server reports its own constants:

  gpu:limits()             submit ceilings, command and node caps
  gpu:version()            { api, protocol, mod } -- branch on api >= N
  gpu:fontMetrics(font)    cell width and height in pixels; the cell
                           height IS the line pitch, so never assume it
  gpu.raw.canvasOps()      canvas op ids and their argument counts
