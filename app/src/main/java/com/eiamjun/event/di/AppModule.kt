package com.eiamjun.event.di

import android.content.Context
import androidx.room.Room
import com.eiamjun.event.data.AppDatabase
import com.eiamjun.event.data.Migrations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * No `fallbackToDestructiveMigration()`, deliberately — see [AppDatabase]. A missing migration
     * has to stop the app at launch rather than quietly delete the orders people are holding
     * tickets for.
     */
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides
    fun menuDao(db: AppDatabase) = db.menuDao()

    @Provides
    fun orderDao(db: AppDatabase) = db.orderDao()

    @Provides
    fun outboxDao(db: AppDatabase) = db.outboxDao()
}
