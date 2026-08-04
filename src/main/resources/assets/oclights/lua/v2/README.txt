OpenGPU v2 library filesystem
=============================

This directory is mounted read-only into any computer connected to an `opengpu`
component. If you can `cat` this file in game, the mount works.

It is deliberately EMPTY of code for now. The v2 Lua library and the rewritten
tutorial are a Stage A deliverable and are not written yet; the mount landed
first because it is what makes them deliverable at all, and because deleting the
legacy stack removes the only other `FileSystem.fromClass` in the repo.

It is also deliberately NOT the legacy `lua/` tree one level up. That code
targets an API that no longer exists — `lib/gpu.lua` binds `component.ocl_gpu`
and calls an `import()` that v2 dropped — so carrying it here would ship a
library that cannot work against this component.

Until the library exists, programs call the raw callback surface directly. It is
documented in docs/dev/API-V2.md. Start with `canvasOps()` if you intend to use
`canvasSubmit`: the numeric op ids are wire internals and that call is the only
way to discover them.
