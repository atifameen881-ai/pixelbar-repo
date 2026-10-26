package dev.anom.pixelbar

import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification

/** Only used to read the small icons of active notifications so the bar can show them. Nothing is stored or sent. */
class NotifListenerService : NotificationListenerService() {

    override fun onListenerConnected() = rebuild()
    override fun onListenerDisconnected() { NotifStore.icons.value = emptyList() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = rebuild()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = rebuild()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = rebuild()

    private fun rebuild() {
        val list: Array<StatusBarNotification> = try { activeNotifications } catch (e: Exception) { emptyArray() }
        val ranking = try { currentRanking } catch (e: Exception) { null }
        val rk = Ranking()
        val seen = HashSet<String>()
        val out = ArrayList<Bitmap>()
        for (sbn in list.sortedByDescending { it.postTime }) {
            // like the real bar: silent notifications don't get a status bar icon
            if (ranking != null && ranking.getRanking(sbn.key, rk) &&
                rk.importance < NotificationManager.IMPORTANCE_DEFAULT) continue
            if (!seen.add(sbn.packageName)) continue
            val bmp = toBitmap(sbn) ?: continue
            out.add(bmp)
            if (out.size >= 8) break
        }
        NotifStore.icons.value = out
    }

    private fun toBitmap(sbn: StatusBarNotification): Bitmap? {
        return try {
            val icon = sbn.notification.smallIcon ?: return null
            val dr = icon.loadDrawable(this) ?: return null
            val sz = 64
            val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
            dr.setBounds(0, 0, sz, sz)
            dr.draw(Canvas(bmp))
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
