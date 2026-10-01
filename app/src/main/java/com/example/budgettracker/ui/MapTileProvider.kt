package com.example.budgettracker.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.CantContinueException
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MapTiles"

/** Per connection attempt; on timeout Android tries the server's next address (e.g. IPv4). */
private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 15_000

/**
 * osmdroid's default tile provider, but downloading with connection timeouts.
 *
 * osmdroid's own downloader sets no timeout, so on networks where the first address
 * of the tile server doesn't answer (typically broken IPv6) every download thread
 * waits for the system TCP timeout (~2 minutes) before falling back.
 */
class AppTileProvider(context: Context, tileSource: ITileSource) :
    MapTileProviderBasic(context, tileSource) {

    // Called from the superclass constructor: must not use fields of this class
    override fun createDownloaderProvider(
        aNetworkAvailablityCheck: INetworkAvailablityCheck?,
        pTileSource: ITileSource?
    ): MapTileDownloader =
        super.createDownloaderProvider(aNetworkAvailablityCheck, pTileSource)
            .apply { setTileDownloader(TimeoutTileDownloader()) }
}

/** Same as osmdroid's TileDownloader.downloadTile, plus timeouts; redirects are followed by HttpURLConnection. */
private class TimeoutTileDownloader : TileDownloader() {

    override fun downloadTile(
        pMapTileIndex: Long,
        redirectCount: Int,
        targetUrl: String?,
        pFilesystemCache: IFilesystemCache?,
        pTileSource: OnlineTileSourceBase
    ): Drawable? {
        if (targetUrl.isNullOrEmpty()) return null
        val tile = MapTileIndex.toString(pMapTileIndex)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = true
                setRequestProperty("User-Agent", MAP_USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Tile $tile: HTTP ${connection.responseCode} ${connection.responseMessage}")
                return null
            }
            val expiration = pTileSource.tileSourcePolicy
                .computeExpirationTime(connection, System.currentTimeMillis())
            val data = connection.inputStream.use { it.readBytes() }
            // Tiles are shown from the cache, so saving them is what makes them appear
            pFilesystemCache?.saveFile(pTileSource, pMapTileIndex, ByteArrayInputStream(data), expiration)
            return pTileSource.getDrawable(ByteArrayInputStream(data))
        } catch (e: BitmapTileSourceBase.LowMemoryException) {
            throw CantContinueException(e)
        } catch (e: IOException) {
            Log.w(TAG, "Tile $tile: $e")
            return null
        } finally {
            connection?.disconnect()
        }
    }
}
