package io.github.vihrea1337.devlog.android.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.vihrea1337.devlog.android.data.AuthRepository
import io.github.vihrea1337.devlog.android.data.AuthRepositoryImpl
import io.github.vihrea1337.devlog.android.data.ProfileRepository
import io.github.vihrea1337.devlog.android.data.ProfileRepositoryImpl
import io.github.vihrea1337.devlog.android.data.ProjectsRepository
import io.github.vihrea1337.devlog.android.data.ProjectsRepositoryImpl
import io.github.vihrea1337.devlog.android.data.ReportsRepository
import io.github.vihrea1337.devlog.android.data.ReportsRepositoryImpl

/**
 * Какую реализацию подставлять, когда кто-то просит интерфейс репозитория.
 * @Binds — короче, чем @Provides: «когда просят AuthRepository, отдай AuthRepositoryImpl».
 * abstract — потому что тело метода не нужно, Hilt генерирует код сам.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun auth(impl: AuthRepositoryImpl): AuthRepository
    @Binds abstract fun projects(impl: ProjectsRepositoryImpl): ProjectsRepository
    @Binds abstract fun reports(impl: ReportsRepositoryImpl): ReportsRepository
    @Binds abstract fun profile(impl: ProfileRepositoryImpl): ProfileRepository
}
