--[[
  Put this disk's lib/ on package.path so `require("opengpu")` works.

  OpenOS mounts a filesystem component at /mnt/<short-address> and then runs this file, passing
  the filesystem proxy as its first argument (boot/90_filesystem.lua does
  `shell.execute(file, _ENV, proxy)`). The default package.path covers /lib, /usr/lib, /home/lib
  and the working directory only -- nothing under /mnt -- so without this the library is present
  on the disk and invisible to require.

  The mount point is looked up rather than derived: OpenOS picks the shortest address prefix
  that is not already taken, so the path is not a fixed function of the address and guessing it
  would break exactly when two disks share a first character.
]]

local fs = require("filesystem")

local function mountOf(proxy)
  if type(proxy) ~= "table" or not proxy.address then
    return nil
  end
  for mounted, path in fs.mounts() do
    if mounted.address == proxy.address then
      return path
    end
  end
  return nil
end

--[[
  Fall back to finding ourselves by content, so running this by hand still works.

  Note the guard is a truthiness test, NOT `type(mounted.exists) == "function"`. Component
  proxy methods are TABLES carrying a __call metamethod -- that is how `print(component.gpu.fill)`
  shows a doc string -- so a type test against "function" is false for every one of them and
  would make this whole fallback dead code.
]]
local function mountByMarker()
  for mounted, path in fs.mounts() do
    if mounted.exists then
      local ok, present = pcall(mounted.exists, "lib/opengpu.lua")
      if ok and present then
        return path
      end
    end
  end
  return nil
end

local mount = mountOf(...) or mountByMarker()
if not mount then
  return
end

local entry = fs.concat(mount, "lib") .. "/?.lua"
if not package.path:find(entry, 1, true) then
  package.path = package.path .. ";" .. entry
end
