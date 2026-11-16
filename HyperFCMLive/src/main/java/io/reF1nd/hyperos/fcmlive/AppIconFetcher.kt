package io.reF1nd.hyperos.fcmlive

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import me.zhanghai.android.appiconloader.AppIconLoader

// Coil 3 port of AppIconLoader's Coil integration (appiconloader-coil only supports Coil 2).
class AppIconKeyer : Keyer<PackageInfo> {
    override fun key(data: PackageInfo, options: Options): String =
        AppIconLoader.getIconKey(data, options.context)
}

class AppIconFetcher(
    private val loader: AppIconLoader,
    private val applicationInfo: ApplicationInfo
) : Fetcher {
    override suspend fun fetch(): FetchResult = ImageFetchResult(
        image = loader.loadIcon(applicationInfo).asImage(),
        isSampled = true,
        dataSource = DataSource.DISK
    )

    class Factory(private val iconSize: Int) : Fetcher.Factory<PackageInfo> {
        override fun create(data: PackageInfo, options: Options, imageLoader: ImageLoader): Fetcher? {
            val applicationInfo = data.applicationInfo ?: return null
            return AppIconFetcher(AppIconLoader(iconSize, false, options.context), applicationInfo)
        }
    }
}
