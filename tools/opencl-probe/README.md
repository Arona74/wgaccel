# GPU test for Worldgen Accelerator

Thank you for helping test! This small program checks whether your graphics card (GPU) can run Worldgen Accelerator's
GPU code **correctly** and **how fast**. It takes about **1 to 3 minutes** and you only have to double-click one file.

It does not install anything, does not touch Minecraft or your worlds, and does not change any setting. It runs a few
calculations on your GPU, compares them with the correct answers, and writes the results to a text file that you send
back.

---

## What you need

- A **Windows** PC (for Linux or macOS, see the end of this page).
- **Java 21 or newer.** If you play Minecraft 1.21 you very likely have it already: the program looks for the Java
  that Minecraft launchers install (Prism Launcher, the official launcher, CurseForge). If it can't find one, it tells you
  what to install (see [Problems](#problems)).
- The two files from this folder, **in the same folder**:
  - `run-probe.bat`
  - `wgaccel-openclprobe.jar`

---

## Steps

1. **Put both files in one folder**, for example a new folder on your Desktop called `gpu-test`.
2. **Close Minecraft** and anything else that uses the GPU heavily (games, video editing, 3D software). A web browser
   or Discord is fine.
3. **Double-click `run-probe.bat`.** A black window opens.
   - If Windows shows a blue box saying *"Windows protected your PC"*, click **More info**, then **Run anyway**. This
     appears for any downloaded `.bat` file.
4. **Wait** until the window says **"Finished"**. Lines of text scroll by while it works. That is normal, and some
   steps pause for a few seconds.
5. When it is finished, a folder window opens with the file **`wgaccel-openclprobe-result.txt`** selected.
   **Send us that file.** Press any key to close the black window.

When you send the file, please also tell us:

- your **GPU model** (for example "Intel Arc A770" or "AMD Radeon RX 7800 XT"),
- whether you have **more than one GPU** (for example a laptop with Intel graphics and an NVIDIA card),
- anything odd you noticed while it ran (see below).

---

## What is normal

- The screen stays as it is: the test draws nothing.
- Your GPU fan may spin up for a moment.
- The text shows lines like `BIT-IDENTICAL` or `DIFFER`, and times like `0.50 ms per launch`. You don't need to read
  them: they are for us.
- On a PC with an NVIDIA card, a second part runs the same test through NVIDIA's own system to compare.

## What to tell us about

These are rare, but if one happens it is useful information, not something you did wrong:

- The screen goes **black for a few seconds** or flickers, or Windows shows *"Display driver stopped responding and has
  recovered"*. Wait for the screen to come back, then send us the result file anyway (it holds everything up to that
  point) and say that it happened.
- The window stays on one line for **more than 5 minutes**. Close it and send us the result file and the last lines
  you saw (a screenshot is fine).

---

## Problems

**"No Java 21 or newer was found"**
Install Java 21 for free: go to <https://adoptium.net>, choose **Temurin 21 (LTS)**, **Windows**, **x64**, download the
**.msi** file and run it with the default options. Then double-click `run-probe.bat` again.

**"That Java is too old for the probe; trying another one..." followed by an error**
This is normal when your PC has several Java versions: the program tries the next one by itself. Only the final message
matters.

**The black window opens and closes immediately**
Make sure both files are in the **same folder**, and that the folder is a normal one (not inside a `.zip`: extract it
first, with right-click → *Extract All*).

**Your antivirus complains**
The `.bat` file only looks for Java and starts `wgaccel-openclprobe.jar` with it. You can open `run-probe.bat` in
Notepad to see exactly what it does.

---

## What the result file contains

Only information about your GPU and the test: the GPU and driver names and versions, the Java and Windows versions, and
the test results and timings. Nothing about you, your files or your Minecraft worlds. You can open it in Notepad
before sending it.

---

## Linux or macOS

Open a terminal in the folder with the files and run:

```
java -jar wgaccel-openclprobe.jar
```

(Java 21 or newer is needed.) The result file `wgaccel-openclprobe-result.txt` appears in the same folder. Note that
macOS has only limited OpenCL support, so most results there will say the GPU is not usable, which is still useful to know.
