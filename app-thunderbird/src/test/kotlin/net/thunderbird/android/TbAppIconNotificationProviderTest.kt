package net.thunderbird.android

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.android.provider.TbAppIconNotificationProvider

class TbAppIconNotificationProviderTest {
    @Test
    fun `provides correct notification icon`() {
        val provider = TbAppIconNotificationProvider()
        val icon = provider.pushNotificationIcon

        assertThat(icon)
            .isEqualTo(net.thunderbird.android.R.mipmap.ic_launcher_monochrome)
    }
}
