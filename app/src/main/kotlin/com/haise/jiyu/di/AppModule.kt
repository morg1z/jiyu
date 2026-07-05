package com.haise.jiyu.di

import android.content.Context
import androidx.room.Room
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.MangaDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "jiyu.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMangaDao(db: AppDatabase): MangaDao = db.mangaDao()

    @Provides
    fun provideChapterDao(db: AppDatabase): ChapterDao = db.chapterDao()
}
