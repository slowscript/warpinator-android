## What to do when Warpinator cannot find / connect to other devices

This document will be updated whenever we discover any common issues.

- Make sure you have the [latest version](https://github.com/slowscript/warpinator-android/releases).  
Minimum required version for the original Linux variant is 1.0.9.

- Make sure both devices are on the same network (same WiFi access point, same router)

- When you change anything on your network (or anything that you do with the intention to solve the problem), restart the application on both sides to make sure it reinitializes itself. 

- Try using your mobile hotspot. This is an easy test whether the problem is caused by the configuration of your router. (Don't forget to restart the app and connect other devices to the hotspot)

- Do not use a VPN (unless you know what you are doing).

- Make sure the firewalls on your computer/phone and router all allow traffic for both the discovery protocol (mDNS, UDP port 5353) and file transfer protocol (UDP and TCP, port is in the settings - default is 42000, you should not change it unless it causes issues).
Temporarily disable the firewall if necessary. 

- Your router might isolate the networks between WiFi and ethernet LAN or even between all clients.
Make sure this is turned off.

- You should be able to ping both ways.
If not, it is a problem with your network.

- Check the IP address the desktop version of Warpinator thinks it has (lower right corner of the window).
Sometimes it gets the IP from a wrong network interface.

- Running `nc -zvu 192.168.xxx.xxx 5353` with the IP of your phone while Warpinator is running should print `192.168.xxx.xxx 5353 port [udp/mdns] succeeded!`.
If it doesn't, please look into the debug log (instructions below) whether it contains something like `Failed to init JmDNS`.
If it is there, then please submit an [issue](https://github.com/slowscript/warpinator-android/issues/new) with the log attached. 

- If the devices see each other but cannot connect, try reconnecting a few times (using the button next to the status icon).
Linux version attempts to reconnect automatically every 30 seconds.
If this fails too, please submit an issue with the log and description of what you have tried so far.

**Getting the debug log:**

Go to setting, turn on "Export debug log" and restart the application.
You will then find it in `Android/data/slowscript.warpinator/files`
