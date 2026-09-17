# ped — Minecraft 1.14.4 / Forge

An operator command that disconnects a player with a fake error message, optionally
showing a random image first. It never terminates Minecraft or deliberately crashes
the JVM. Install the same mod JAR on the server and all connecting clients.

## Requirements

- Minecraft Java Edition **1.14.4**
- Forge **28.2.26**
- **Java 8 JDK** for development (the bundled Gradle 4.9 cannot run on modern Java)

## Build and develop

```bash
./build.sh                 # Build the installable JAR
./build.sh runClient       # Launch a development client
./build.sh runServer       # Launch a development dedicated server
./build.sh genIntellijRuns # Generate IntelliJ run configurations
```

`build.sh` uses `JAVA8_HOME` when set, then `.tools/java8`, then the Arch Linux
Java 8 JDK. To install a pinned, checksum-verified project-local Temurin JDK on
Linux x86_64, run `./scripts/setup-java8.sh`. This avoids the legacy ForgeGradle
ZIP compression error seen with CachyOS's system Java and zlib-ng. No system Java
settings are changed. On other systems set `JAVA8_HOME` to a Java 8 JDK directory.
The build helper keeps Gradle's cache
inside `.gradle/user-home`. The first build downloads the Forge/Minecraft toolchain.

On Windows, set `JAVA_HOME` to a Java 8 JDK and use `gradlew.bat build`.

Output: `build/libs/mc-crash-mod-0.4.0.jar`. Copy this JAR into each installation's
`mods` directory. Client and server development files are kept separately under
`run/client` and `run/server`. Read Minecraft's EULA and accept it yourself in the
development server's generated `eula.txt` before running that server.

## Commands

```mcfunction
/crash PlayerName
/crash PlayerName image
/crash PlayerName image https://example.com/prank.png
/crash reload
```

Requires operator permission level **3**, matching `/kick`; server console also
works. The target must be one online player. The first command kicks immediately.
Image mode selects from the configured array using `imageSelection`, loads the
image on the server, sends it to the target,
and displays it against a black background. Once the client displays it, the
server starts the kick countdown (three seconds by default). Server lag can
extend the countdown. Escape can close the image screen without cancelling a
countdown that has already started. Players can reconnect normally.

Image loading happens on two background workers with a bounded queue. A missing
file, failed download, unsupported image, or display timeout reports an error to
the command sender and does **not** kick the target. Duplicate pending requests
are rejected. Disconnecting early cancels the pending request.

## Image configuration: local paths and URLs

Edit `config/fakecrash-common.toml` in the **server's game directory** (or your
Prism instance's `minecraft/config/` folder for single-player/LAN hosting):

```toml
images = [
    "/home/thedoctorttv/Pictures/prank_one.png",
    "/mnt/games/prank images/prank_two.jpg",
    "config/ped/images/prank_three.png",
    "https://example.com/prank_four.png"
]
imageSeconds = 3
imageSelection = "random" # or "sequential"
```

These are examples; replace them with your actual image files and direct image
URLs. Local paths and URLs can be mixed in the same array. Relative paths start
at the server game directory, not the config folder. On Windows, use forward
slashes (`C:/Pictures/prank.png`) or TOML literal strings (`'C:\Pictures\prank.png'`).
`~` and environment variables are not expanded.

The server reads/downloads the selected image and sends its pixels to the target.
**Clients do not need the original file, URL, or a resource pack.** On a remote
server, paths must exist on that server; your client config does not configure
that server. Duplicate entries increase that image's selection probability.

PNG, JPEG, and animated GIF are supported. Use a direct image
URL, not an image hosting website's HTML page. HTTP and HTTPS are supported,
including up to three redirects. Images are limited to 8 MiB and 4096×4096 source
pixels. Static images are resized proportionally to at most 512×512 (256×256 if
needed). GIFs retain frame timing, transparency, partial-frame offsets, and disposal
operations, and loop for the entire display period. Zero-delay frames use 100 ms;
other delays have a 20 ms minimum. The GIF's embedded finite loop count is ignored.

GIFs support up to 120 frames and start at a maximum of 256×256 per frame, reducing
to 128×128 or 64×64 when needed to fit the 512 KiB transfer limit. Animations that
still exceed that limit, the decode-work limit (256 million source pixels), or the
loading timeout report an error without kicking. The client validates frame count,
frame sizes, and total decoded memory before creating textures. Textures are freed
when the screen closes. The display preserves the image's aspect ratio.

`imageSeconds` accepts 1–15. `imageSelection = "random"` picks randomly (the
default); `imageSelection = "sequential"` cycles through the array from first to
last, then starts again. The sequence is shared across all command senders and
targets. Each queued array-based request advances it, even if loading later fails;
rejected/busy requests do not consume an entry.

Supply a direct HTTP/HTTPS URL after `image` to override the array for that one
request. It works with an empty array, ignores the selection mode, does not change
the config, and does not advance the sequence. Paste the URL without quotes; encode
spaces as `%20`. Query strings with `?` and `&` are supported. The usual download
and image limits still apply. Command overrides accept URLs, not local file paths.

After saving the config, run **`/crash reload`** as an operator (permission level 3)
or `crash reload` in the server console. Reload applies a validated snapshot of the
server config and resets the sequence to the first entry. Invalid reloads report
an error and retain the previous active settings. Settings take effect on future
requests; pending requests retain their image and delay. Restarting the server or
reopening the single-player world also reloads the settings and resets the sequence.
Forge may watch the file, but this mod uses its own active snapshot so `/crash reload`
is the explicit apply step during play.

An empty array requires a command URL for image mode; `/crash PlayerName` still works.

The in-game display name and internal mod ID are **ped**. The mod page uses the
neutral description “Player Experience Display.” The config remains named
`fakecrash-common.toml` explicitly to preserve existing settings from older versions.
Version 0.4.0 changes the mod ID/network channel and image packet format: update
**both server and clients**, removing older JARs from each `mods` folder. Commands
and the existing config schema are unchanged.

## Manual verification

Use a disposable development server and two clients with this mod installed:

- As a non-operator, confirm `/crash` is unavailable.
- As an operator, use the plain command; only the named target should disconnect
  and should be able to reconnect normally.
- With an empty image list, image mode should report an error without kicking.
- Configure a local path and an HTTP/HTTPS URL; repeat image mode and confirm
  both can appear, followed by a kick after the configured delay. Only the target
  should see the image. Check that a missing file or failed URL does not kick.
- Try an animated GIF from both a file and a URL; confirm movement, timing, and
  looping until the kick. Close the display early and repeat to check cleanup.
- Set `imageSelection = "sequential"`, reload, and confirm array order and wrapping.
- Supply a command URL with an empty array; confirm it works without editing config.
- Reload with a new delay/list and confirm it affects subsequent requests. Invalid
  TOML should report an error while previous active settings continue working.
- Disconnect during the image delay, reconnect, and confirm there is no stale kick.
- Confirm a dedicated server starts without loading client GUI classes.

`./build.sh build` compiles Java, runs the image-loader tests, and produces the
remapped Forge JAR. Tests cover local paths, image conversion, HTTP redirects,
failed downloads, size limits, sequential wrapping, URL override precedence, and
config snapshot reload/validation, GIF frame composition/disposal, playback timing,
animation URL loading, and animation packet bounds. In-game rendering, multiplayer timing, and
full dedicated-server mod loading still require manual verification. The initial
development server check reached its EULA gate (`eula=false`); this legacy Forge
launcher can report a Gradle daemon-disappeared error when it exits at that gate.

## Project layout

- `FakeCrash.java`: configuration, operator command, server-side kick scheduling.
- `ImageLoader.java`: bounded local-file/URL loading and image conversion.
- `GifDecoder.java`: GIF frame composition and disposal handling.
- `ImageAnimation.java`: bounded frame bundles and playback timing.
- `ImageSelection.java`: random/sequential selection and URL override validation.
- `PrankSettings.java`: immutable settings snapshots for explicit reload.
- `PrankNetwork.java`: image transfer and target-only display acknowledgement.
- `ClientPrank.java`: client-only display screen.
- `src/main/resources/META-INF/mods.toml`: Forge and Minecraft compatibility.

Build tooling is based on the [official Forge 1.14.4 MDK](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.14.4.html).
