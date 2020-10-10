package ir.mrahimy.conceal.di

import ir.mrahimy.conceal.repository.InfoRepository
import ir.mrahimy.conceal.repository.InfoRepositoryImpl
import ir.mrahimy.conceal.repository.RecordingRepository
import ir.mrahimy.conceal.repository.RecordingRepositoryImpl
import ir.mrahimy.conceal.repository.SampleRepository
import ir.mrahimy.conceal.repository.SampleRepositoryImpl
import org.koin.dsl.module

val repositoryModule = module {
    factory<RecordingRepository> { RecordingRepositoryImpl(get()) }
    factory<InfoRepository> { InfoRepositoryImpl(get()) }
    factory<SampleRepository> { SampleRepositoryImpl() }
}