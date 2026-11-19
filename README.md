# ModForge

Android app that mods other APKs with a free AI model (OpenRouter). The heavy work runs in
free GitHub Actions, so nothing heavy runs on your phone or laptop.

How it works: you pick an APK and type what to change. The app uploads it to your GitHub repo,
a cloud job decompiles it, the AI edits the files, the job rebuilds and signs it, and the app
downloads the result and offers to install it.

## One-time setup (about 10 minutes, all free)

1. **Create the repo.** On github.com create a new **private** repo named `modforge` and upload
   everything in this folder (including the hidden `.github` folder) with
   Add file > Upload files, using a desktop browser so folders can be dragged in.
   Use the `main` branch.
2. **Add the AI key.** Get a free key at openrouter.ai/keys. In your repo go to
   Settings > Secrets and variables > Actions > New repository secret.
   Name: `OPENROUTER_API_KEY`, value: your key.
3. **Build the app.** Repo > Actions > build-app > Run workflow. When it finishes, open the run
   and download the `ModForge-apk` artifact (a zip), unzip it, and install `app-debug.apk`
   on your phone.
4. **Make a token.** GitHub > Settings > Developer settings > Fine-grained tokens > Generate.
   Select only your `modforge` repo. Repository permissions: **Contents: Read and write**,
   **Actions: Read and write**. Copy the token.

Open ModForge, enter `your-username/modforge` and the token once, then use it.

## Notes

- Only mod apps you own, open-source apps, or ones you have permission to change.
- The modded APK is signed with a different key than the original, so uninstall the original
  before installing it. Apps with server-side checks (online games, payments, Play Integrity)
  will not work after modding.
- Free models are rate limited and sometimes fail. The job tries several automatically. If it
  fails, open the link shown in the app to see why, then try again with a simpler request.
- Works best for text, theme, colors, names and simple on-off changes. Large or obfuscated apps
  are hit and miss.
- The token is stored on your phone. Revoke it on GitHub if you lose the phone.
- APK limit is 40 MB in this simple version.
