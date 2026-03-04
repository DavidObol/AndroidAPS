package app.aaps.pump.common.di

import android.content.Context
import app.aaps.pump.common.hw.rileylink.service.RileyLinkInteractionLogger
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
object RileyLinkProviderModule {

    @Provides
    @Singleton
    fun provideRileyLinkInteractionLogger(context: Context): RileyLinkInteractionLogger =
        RileyLinkInteractionLogger(context)
}
