Welcome to NoInvalidSessionID!
This mod removes the pesky "Invalid Session" so you never have to relaunch your game to start a new session ever again!

When you first launch with this mod, you will get a message on your multiplayer screen saying to go to an official Microsoft site to put in a code that the mod will generate. All that this does is tell Microsoft, "This is my Session ID token currently, please allow access so every time I go to the multiplayer screen, I refresh this token".

After you put in the ID ONCE, it should work forever, no additional code required after that point. This mod is designed to work forever, thouh if you unlikely experience problems after 90 days, it's recommended to log in again. The code will expire after 90 days of inactivity, so in that case, follow the on screen instructions to put the code in again. However, that is only in the case of inactivity, so if you play at any point in that time frame, your session ID will never expire.

In the Gallery tab, both screens that you should see are shown, so you know if it's right or not.

Small Note: It's recommended to restart your game every once in a while, not due to the mod, but because Minecraft can become a power user of your system RAM and CPU over time.

Security & Privacy
This mod performs a Microsoft OAuth 2.0 login to refresh your session. Here is exactly what it does and does not do:

What it stores: A Microsoft refresh token, AES-256-GCM encrypted, saved to ~/.noinvalidsessionid/token.enc outside your Minecraft folder. On Mac and Linux the file is additionally restricted to your OS user account only (permissions set to 600).

What it never does: The mod never stores your Microsoft password, never sends your credentials anywhere except official Microsoft and Minecraft servers, and never communicates with any third-party server. Endpoints used:

login.live.com — Microsoft authentication
user.auth.xboxlive.com — Xbox Live token exchange
xsts.auth.xboxlive.com — Xbox security token
api.minecraftservices.com — Minecraft session token and profile verification

Open source: The full source code is available right here so you can verify exactly what the mod does. Revoking access: You can revoke access at any time by going to account.microsoft.com → Security → Connected apps and removing it, or simply deleting ~/.noinvalidsessionid/token.enc.
