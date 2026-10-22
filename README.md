<div align="center"> 
<picture>
    <source
      width="512px"
      media="(prefers-color-scheme: dark)"
      srcset="https://raw.githubusercontent.com/MorpheApp/.github/refs/heads/main/profile/assets/morphe-wordmark/morphe_wordmark_dark.svg"
    />
    <img 
      width="512px"
      alt="Morphe"
      src="https://raw.githubusercontent.com/MorpheApp/.github/refs/heads/main/profile/assets/morphe-wordmark/morphe_wordmark_light.svg"
    />
</picture>

[![Releases badge](https://img.shields.io/badge/Releases-gray?style=for-the-badge&logo=github)](https://github.com/Desplicableme/morphe-manager/releases/latest) [![Documentation badge](https://img.shields.io/badge/Documentation-gray?style=for-the-badge&logo=github)](docs/README.md) [![Guides badge](https://img.shields.io/badge/Guides-gray?style=for-the-badge&logo=bookstack&logoColor=white)](docs/README.md) [![Issues badge](https://img.shields.io/badge/Issues-gray?style=for-the-badge&logo=github)](https://github.com/Desplicableme/morphe-manager/issues)
<br>
</div>

&nbsp;
<p align="center">
  <a href="https://github.com/Desplicableme/morphe-manager/releases/latest" title="Download Morphe Enhanced Edition">
    <img src="https://raw.githubusercontent.com/MorpheApp/.github/refs/heads/main/profile/assets/download-morphe.svg" alt="Download Morphe Enhanced Edition" width="240"/>
  </a>
</p>
&nbsp;

# 💊 Morphe (Enhanced Edition)

[![Release](https://img.shields.io/github/v/release/Desplicableme/morphe-manager?color=blue&label=Latest%20Enhanced%20Build)](https://github.com/Desplicableme/morphe-manager/releases/latest)
[![Build Status](https://img.shields.io/github/actions/workflow/status/Desplicableme/morphe-manager/build_and_publish.yml?label=Build%20%26%20Publish)](https://github.com/Desplicableme/morphe-manager/actions)
[![Upstream Sync](https://img.shields.io/github/actions/workflow/status/Desplicableme/morphe-manager/sync_upstream.yml?label=Upstream%20Sync)](https://github.com/Desplicableme/morphe-manager/actions)

> Enhanced, fully self-contained fork of [Morphe](https://github.com/MorpheApp/morphe-manager) based on **v2.0.0** with an **in-built APKMirror downloader**, **silent background verification**, **split APK extraction**, and **storage auto-cleanup**.

---

### 🚀 What's New in This Enhanced Edition?

* 📥 **In-Built APKMirror Downloader**: Directly searches, crawls, and downloads required APKs and APKM/XAPK split bundles inside Morphe with live speed, byte progress, and auto-attachment to the patcher pipeline.
* 🛡️ **Silent Background Cloudflare Bypass**: Automatically evaluates challenges and download triggers offscreen. You never see a web dialog unless an interactive captcha click is strictly required, at which point an in-app verification dialog lets you solve it seamlessly without leaving the app.
* ⚡ **Installed Split APK Extraction**: If the app is already installed on your device, Morphe directly extracts the base and split APKs via Android's `PackageManager`, bypassing external downloads entirely.
* 🔄 **Continuous In-App Updates**: Built-in release update channels point directly to this fork with consistent cryptographic release signing so you never have to uninstall your previous release to update.
* 🧹 **Complete Storage Management Suite**:
  - **1-Tap Quick Clean**: Instantly purges patcher workspace folders, network caches, and orphaned temporary files.
  - **Auto-Deletion Settings**: Opt-in toggles to automatically wipe downloaded source APKs and clear caches after patching successfully finishes.
* ⚙️ **v2.0.0 Core Architecture**: Built on Morphe's high-efficiency `:patcher-core` engine with embedded JDK 25 runtime and Material 3 Expressive UI.

[⬇️ **Download Latest Enhanced APK (v2.0.0-enhanced)**](https://github.com/Desplicableme/morphe-manager/releases/latest)

---

## ✨ Why Morphe?

Modern apps are full of ads and dark patterns designed to grab your attention. Morphe patches them at the bytecode level - modifying the app directly on your device, without any of your data leaving it. The result is a cleaner version of the app you already know, exactly the way you want it.

## 📲 Download Enhanced Edition

**[→ Download Latest APK from GitHub Releases](https://github.com/Desplicableme/morphe-manager/releases/latest)**

Download the pre-compiled, signed `morphe-manager-2.0.0-enhanced.apk` from the Releases section. No account needed.

## 🚀 How it works

1. **Install Morphe Enhanced Edition** from [GitHub Releases](https://github.com/Desplicableme/morphe-manager/releases/latest).
2. **Pick an app** - YouTube, YouTube Music, or Reddit are supported out of the box.
3. **Choose your mode:**
    - **Simple mode** - designed for a one-tap experience. Just tap Patch and Morphe handles the rest with sensible defaults. No configuration needed.
    - **Expert mode** - gives you full control. Choose exactly which of the 100+ patches to apply, configure per-patch options (colors, toggles, and more), and fine-tune everything before patching.
4. **Provide the APK** - Morphe guides you through obtaining the original app file via step-by-step dialogs. The patching itself happens entirely on your device.
5. **Install and enjoy** - once patching is complete, install the result like any normal APK.

Everything happens locally. Morphe never uploads your APKs or personal data anywhere.

## 📖 Guides

Step-by-step walkthroughs with screenshots, covering patching in both modes, installers, updates, patch sources, backups, and customization: **[→ Morphe guides](docs/README.md)**

## 🔧 Features

**Patching**
- Simple mode for one-tap patching with curated defaults
- Expert mode for full patch selection, per-patch configuration, and experimental version support
- Expert mode also shows an expanded patching screen with real-time logs and live RAM usage monitoring during patching
- 100+ patches for YouTube, YouTube Music, and Reddit
- Support for split APKs
- Optional "Optimize for device architecture" mode - skips split APK modules for unsupported CPU architectures, locales, and screen densities during merge, and strips native libraries for unsupported architectures from plain APKs after patching
- Sends a notification the moment patching finishes, so you don't have to keep the app open
- Optional completion sound, with a distinct tone for success and failure
- Optional auto-install right after patching completes, through Shizuku, or through the system installer where Android lets Morphe replace a build it installed itself
- Batch patching - select several apps and patch them in one queue, with every question asked
  up front so the run never stops to wait for you
- A re-patch banner on the home screen when a patch source releases changes for your apps, counting them and queueing them all in one tap

**Patch options** *(Simple mode: available in the Advanced tab; Expert mode: available on the patch selection screen)*
- Custom app display name, launcher icon, and header logo per app, with built-in creators that generate every density variant for you
- App theme colors (background color presets)
- Hide Shorts app shortcut and widget (YouTube)
- And more, depending on installed patch bundles

**Patch sources**
- Add any compatible patch bundle via GitHub URL or deep link
- Per-source pre-release toggle to get early patch access
- Automatic background update notifications (even when the app is closed)
- Sort your app list and patch sources however you like (name, install date, and more)

**Installer**
- Standard Android installer
- Shizuku, Shizuku+ or Sui for installs with no confirmation dialog
- Root installer with Magisk module support (mount-based, no data loss on update)
- Play Store installer variants, so Google Play recognizes itself as the install source (with a warning about the trade-off - Play Store may then offer updates that would overwrite your patched build)
- Any third-party installer apps detected on the system are also available as an option
- Prompt-on-install option to choose per session
- On rooted devices, Morphe asks whether you want a Root Mount install or a Standard install before patching starts, and adjusts the applied patches to match your choice

**Appearance**
- System / Light / Dark / Material You themes
- Pure Black mode for OLED screens
- Accent color selection
- Animated backgrounds - pick one you like, or let Morphe shuffle them for you on each launch, daily, or every three days
- App icon selection

**Home screen**
- Friendly time-of-day greeting when you open the app
- Rearrange your app list into the order that suits you
- Group your apps by patch source or your own categories
- Hide apps you never patch, and bring them back whenever you like
- Home cards for apps patched with universal patches, not just app-specific ones
- Multi-select and bulk actions for cleaning up saved APKs and patch selections, and for
  patching several apps in one queue
- Launcher shortcuts for re-patching outdated apps, checking for updates, and jumping straight
  into patching a recently patched app
- Floating scroll-to-top button when your lists get long
- A short guided tour after your first patch, so you know where everything lives

**Advanced**
- Import/export your Morphe settings as JSON, with a Replace or Merge choice on import
- Import/export your signing keystore
- Manage saved original APKs and patched APKs
- Manage saved patch selections per app
- GitHub Personal Access Token support for higher API rate limits
- Process runtime - run patching in a separate process for better stability, with configurable memory limit
- Bytecode processing mode - controls how bytecode is processed during patching, affecting patching speed, memory usage, and output APK size
- Built-in file picker as an alternative to the system one, with an option to show hidden files
- Optional external trigger, so automation apps can queue a batch through an intent, gated by
  a per-app confirmation
- Export debug logs for troubleshooting

## ❓ New to GitHub?

If you ended up here but aren't sure what to do next - no worries. Here's the short version:

1. Go to the **[Releases page](https://github.com/Desplicableme/morphe-manager/releases/latest)**.
2. Under **Assets**, tap `morphe-manager-2.0.0-enhanced.apk` to download it.
3. Open the downloaded file on your Android device and tap **Install**.
4. If Android asks you to allow installs from unknown sources, follow the prompt to enable it - this is required for any app not from the Play Store.

That's it. Once Morphe is installed, everything else happens inside the app - the **[guides](docs/README.md)** above walk you through the first patch.

For issues or questions regarding this enhanced edition, please open an issue on **[GitHub Issues](https://github.com/Desplicableme/morphe-manager/issues)**.

## 📙 Contributing

Thank you for considering contributing to Morphe.
You can find the contribution guidelines [here](CONTRIBUTING.md).

## ❗ About

Morphe is built on the foundation of [ReVanced Manager](https://github.com/ReVanced/revanced-manager) and [URV](https://github.com/Jman-Github/Universal-ReVanced-Manager). All changes made by Morphe are documented in the Git history.

## 📜 License

Morphe is licensed under the [GNU General Public License v3.0](LICENSE), with additional conditions under GPLv3 Section 7:

- **Name & Branding Restrictions (7c & 7e):** Derivative works must use their own distinct branding. The **"Morphe"** name, logos, and trademarks may not be used for the branding or title of derivative works (e.g., names like *"Morphe Plus"*, *"Morphe Expanded"*, or *"Morphe UserXYZ"* are strictly prohibited).

See the [LICENSE](LICENSE) file for the full GPLv3 terms and the [NOTICE](NOTICE) file for full conditions of GPLv3 Section 7.
