# PiPup

PiPup is an application that allows displaying user-defined custom notifications on Android TV.

The most common use-case for this application is for sending notifications, from a home-automation solution, to your Android TV.

![](https://github.com/rogro82/PiPup/raw/master/graphics/screenshot-1.png)

__Some example scenarios:__

- Show a snapshot of your camera on your TV (eg on a motion trigger)
- Display a notification with the video of your camera when someone is at your door
- Send a notification when your dryer/washingmachine is ready
- Anything else you might find useful


__The application is currently in a `public beta`__

To enter the `beta` and install the application on your device go to:  
https://play.google.com/apps/testing/nl.rogro82.pipup

_Important: after installation / updating it is currently adviced to restart your TV and open the application once to make sure the background-service is running_

#### Sideloading:

On Android TV (8.0+), when sideloading, you will need to set the permission for SYSTEM_ALERT_WINDOW manually (using adb) as there is no interface on Android TV to do this.

To give the application the required permission to draw overlays you will need to run:
```
adb shell appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow
```

## Integrating

PiPup uses an embedded webserver (NanoHTTPD) which runs on port 7979.

### Sending notifications

#### To send notifications with an external media resource (image, url or webview) use application/json


| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /notify          |
| Method:       | POST             |
| Content-Type: | application/json |

Example json data:

```json
{
  "duration": 30,
  "position": 0,
  "title": "Your awesome title",
  "titleColor": "#0066cc",
  "titleSize": 20,
  "message": "What ever you want to say... do it here...",
  "messageColor": "#000000",
  "messageSize": 14,
  "backgroundColor": "#ffffff",
  "media": { "image": {
    "uri": "https://mir-s3-cdn-cf.behance.net/project_modules/max_1200/cfcc3137009463.5731d08bd66a1.png", "width": 480
  }}
}
```
All fields are optional and for `media` you can specify 4 types:

```json 
{ "image": { "uri": "address_to_your_image", "width": 480 }}
{ "video": { "uri": "address_to_your_video", "width": 480 }}
{ "mjpeg": { "uri": "address_to_your_mjpeg_stream", "width": 480 }}
{ "web":   { "uri": "address_to_your_resource", "width": 640, "height": 480 }}
```

#### Playing over an app that is already playing

A TV is almost always already playing something, and that used to stop a video
popup from appearing. Three things changed:

- Video renders into a `TextureView` rather than a `SurfaceView`, so the popup is
  composited above the running app's video surface instead of behind it.
- `preferSoftwareDecoder` (default `true`) keeps the popup off the hardware video
  decoder, which the foreground app is usually holding. Most boxes expose only
  one or two hardware decoder instances; a 480px popup costs very little to
  decode in software. Set it to `false` for a large, high-bitrate popup.
- `audioFocus` (default `"none"`) decides what happens to the running app's
  sound. `"none"` plays the popup silently and leaves the show alone, `"duck"`
  asks the running app to lower its volume, `"pause"` asks it to stop.

You should no longer need to send a stop/quit command to the TV first.

Measured on a Sony BRAVIA 4K UR2 (Android 10, MediaTek), popup fired at a
fullscreen HLS stream playing in the built-in player, with no stop command sent
and both decoders live at once:

| Setting                         | Time to first frame |
| ------------------------------- | ------------------- |
| `preferSoftwareDecoder: true`   | 245 ms              |
| `preferSoftwareDecoder: false`  | 296 ms              |

Software decoding was not slower here, and it skips the vendor OMX component
negotiation entirely, which is why it is the default.

| Field                   | Default  | Meaning                                          |
| ----------------------- | -------- | ------------------------------------------------ |
| `audioFocus`            | `"none"` | `none` / `duck` / `pause`                        |
| `volume`                | `0`      | Popup volume, `0.0`-`1.0`                        |
| `preferSoftwareDecoder` | `true`   | Avoid contending for the hardware video decoder  |
| `loop`                  | `true`   | Loop a short clip for the whole `duration`       |

#### Use `mjpeg` for camera streams

For a camera, `mjpeg` is much faster than `video`: there is no manifest or
container to probe and no video decoder involved, so the first frame usually
appears in well under a second rather than the ten or more seconds an HLS or
RTSP handshake can take. Home Assistant serves exactly this shape of stream:

```json
{
  "duration": 30,
  "title": "Front door",
  "media": { "mjpeg": {
    "uri": "http://homeassistant.local:8123/api/camera_proxy_stream/camera.front_door?token=<signed_token>",
    "width": 640
  }}
}
```

### Checking status

| Property | Value     |
| -------- | --------- |
| Path:    | `/status` |
| Method:  | GET       |

Returns JSON with the app version, the listening address, whether the overlay
permission has been granted, and whether a popup is currently showing. Useful as
a Home Assistant availability check.

### Autostart

The service starts on boot, on the OEM "quickboot" broadcast that TVs send when
they resume from a soft power-off, and after an app update. Because those
broadcasts are all individually unreliable on TV firmware, a persisted
`JobScheduler` job also checks every 15 minutes that the service and its socket
are alive and revives them if not -- JobScheduler restores persisted jobs across
reboots itself, so this keeps working even when every broadcast is missed.

Open the app once after installing so it can grant itself the watchdog job, then
check the status screen: it reports the overlay permission and battery
optimisation state, which are the two settings that otherwise fail silently.

Verified on the device: killing the process the way the system does under memory
pressure (`am kill`) brings the service back on its own within a few seconds.

One case nothing can recover from, by Android's design rather than by omission:
if the app is explicitly **force-stopped** (from the system app settings, or
`am force-stop`), Android puts it in the stopped state and cancels its scheduled
jobs. No broadcast and no job can start it again until it is launched once from
the menu. Launching it restores the watchdog automatically.

If the status screen warns about battery optimisation and the TV has no settings
screen for it, whitelist it over adb:

```
adb shell dumpsys deviceidle whitelist +nl.rogro82.pipup
```

#### To send notifications with an image file use multipart/form-data

| Property      | Value               |
| ------------- | ------------------- |
| Path:         | /notify             |
| Method:       | POST                |
| Content-Type: | multipart/form-data |

Form-fields:

| Field           | Type                                         |
| --------------- | -------------------------------------------- |
| duration        | Integer (default=30)                         |
| position        | Integer (0..4, default=0)                    |
| title           | String                                       |
| titleSize       | Integer (default=16)                         |
| titleColor      | string (default=#FFFFFF, format=[AA]RRGGBB   |
| message         | String                                       |
| messageSize     | Integer (default=12)                         |
| messageColor    | String (default=#FFFFFF, format=[AA]RRGGBB   |
| backgroundColor | String (default=#CC000000, format=[AA]RRGGBB |
| image           | File                                         |
| imageWidth      | Integer (default=480)                        |

`position` is an enum ranging from 0 to 4

|  | Position    |
| -----: | ----------- |
| 0     | TopRight    |
| 1     | TopLeft     |
| 2     | BottomRight |
| 3     | BottomLeft  |
| 4     | Center      |

Color-properties are in `[AA]RRGGBB` where the alpha channel is optional e.g. #FFFFFF or #CCFFFFFF