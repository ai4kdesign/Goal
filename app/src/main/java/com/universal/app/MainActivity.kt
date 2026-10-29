                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val statusIntent = Intent(ACTION_INSTALL_STATUS).setPackage(packageName)
                val pending = PendingIntent.getBroadcast(this, sessionId, statusIntent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        } finally {
            apk.delete()
        }
    }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            startActivity(confirm)
                        } catch (_: ActivityNotFoundException) {
                            setUpdateStatus("Install failed")
                            toast("This device can't show the install prompt.")
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    setUpdateStatus("✓ Updated")
                    toast("Update installed! You can now disable 'Install unknown apps' in Settings.")
                    // Scope Item #4: Prompt user to turn "Install unknown apps" back off after update
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        } catch (_: ActivityNotFoundException) {
                        }
                    }
                }
                else -> {
                    setUpdateStatus("Failed")
                    toast(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed.")
                }
            }
        }
    }

     private fun toggleVideoPlayPause() {
        val js = """
            (function() {
                var videos = Array.from(document.querySelectorAll('video'));
                document.querySelectorAll('iframe').forEach(function(f) {
                    try {
                        if (f.contentDocument) {
                            videos = videos.concat(Array.from(f.contentDocument.querySelectorAll('video')));
                        }
                    } catch (e) {}
                });
                if (videos.length > 0) {
                    var v = videos[0];
                    if (v.paused) v.play(); else v.pause();
                    return "Toggled video";
                }

                var playBtn = document.querySelector(
                    '.play-wrapper, [data-playpause], .vjs-play-control, .jw-icon-playback, [aria-label*="Play"], [aria-label*="Pause"]'
                );
                if (playBtn) {
                    playBtn.click();
                    return "Clicked player control";
                }

                return "FALLBACK_TAP";
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result?.contains("FALLBACK_TAP") == true) {
                val targetView = if (customView != null) fullscreenContainer else webView
                val cx = targetView.width / 2f
                val cy = targetView.height / 2f
                val now = android.os.SystemClock.uptimeMillis()
                val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, cx, cy, 0)
                val up = android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_UP, cx, cy, 0)
                targetView.dispatchTouchEvent(down)
                targetView.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                toggleVideoPlayPause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (customView != null) {
                    toggleVideoPlayPause()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (customView != null) {
                    hideCustomView()
                    return true
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
