package com.example.data.util

import com.example.data.local.entity.ActorEntity
import com.example.data.local.entity.LinkEntity
import com.example.data.local.entity.StudioEntity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Clean & Updated dataset replacing previous test data completely.
 * All entries include Cover URLs, StashDB covers, exact dates, and verified magnets/streams.
 */
object SampleTestDataset {

    private fun parseDateToMillis(dateStr: String): Long {
        return try {
            val format = SimpleDateFormat("d MMM yyyy", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            format.parse(dateStr.trim())?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    // Actors present in this updated dataset
    val sampleActors: List<ActorEntity> = listOf(
        ActorEntity(id = "actor_payton_preslee", name = "Payton Preslee", imageUrl = ""),
        ActorEntity(id = "actor_adriana_maya", name = "Adriana Maya", imageUrl = ""),
        ActorEntity(id = "actor_jewelz_blu", name = "Jewelz Blu", imageUrl = ""),
        ActorEntity(id = "actor_luna_luxe", name = "Luna Luxe", imageUrl = ""),
        ActorEntity(id = "actor_jazmin_black", name = "Jazmin Black", imageUrl = ""),
        ActorEntity(id = "actor_ella_reese", name = "Ella Reese", imageUrl = ""),
        ActorEntity(id = "actor_haru_minami", name = "Haru Minami", imageUrl = ""),
        ActorEntity(id = "actor_mandi_love", name = "Mandi Love", imageUrl = ""),
        ActorEntity(id = "actor_cory_chase", name = "Cory Chase", imageUrl = ""),
        ActorEntity(id = "actor_jill_taylor", name = "Jill Taylor", imageUrl = ""),
        ActorEntity(id = "actor_nia_bleu", name = "Nia Bleu", imageUrl = ""),
        ActorEntity(id = "actor_elana_bunnz", name = "Elana Bunnz", imageUrl = ""),
        ActorEntity(id = "actor_luxe_lafox", name = "Luxe LaFox", imageUrl = ""),
        ActorEntity(id = "actor_raissa_bellini", name = "Raissa Bellini", imageUrl = ""),
        ActorEntity(id = "actor_remy_woods", name = "Remy Woods", imageUrl = "")
    )

    // Studios present in this updated dataset
    val sampleStudios: List<StudioEntity> = listOf(
        StudioEntity(id = "studio_new_sensations", name = "New Sensations"),
        StudioEntity(id = "studio_freeuse_fantasy", name = "Freeuse Fantasy"),
        StudioEntity(id = "studio_girlsway", name = "Girlsway"),
        StudioEntity(id = "studio_s1", name = "S1"),
        StudioEntity(id = "studio_taboo_heat", name = "Taboo Heat"),
        StudioEntity(id = "studio_cuckold_sessions", name = "Cuckold Sessions"),
        StudioEntity(id = "studio_spizoo", name = "Spizoo"),
        StudioEntity(id = "studio_my_pervy_family", name = "My Pervy Family"),
        StudioEntity(id = "studio_hot_wife_xxx", name = "Hot Wife XXX"),
        StudioEntity(id = "studio_mommys_boy", name = "Mommy's Boy"),
        StudioEntity(id = "studio_brazzers_exxtra", name = "Brazzers Exxtra"),
        StudioEntity(id = "studio_ass_parade", name = "Ass Parade")
    )

    // Scenes (Links) test dataset
    val sampleScenes: List<LinkEntity> = listOf(
        // Scene 1: Payton Preslee Plays With Her New Boy Toy
        LinkEntity(
            id = "scene_new_1",
            title = "Payton Preslee Plays With Her New Boy Toy",
            coverImage = "https://stashdb.org/images/05b4efb8-a322-47d9-8e79-8315293786ec",
            urlHD = "https://www.eporner.com/video-XjkELE1lejV/dana-horny-slave-got-hard-treat-by-tunisian-french-mistress/",
            url4K = "https://www.eporner.com/video-XjkELE1lejV/dana-horny-slave-got-hard-treat-by-tunisian-french-mistress/",
            magnet = "magnet:?xt=urn:btih:e2fcde2251f9aaa88dd1b22e5aaafc109a511c41&dn=NewSensations 25 10 04 Payton Preslee XXX 1080p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            magnet4K = "magnet:?xt=urn:btih:ceb9d60b397c7c05bf472f02157fc81c7d00a222&dn=NewSensations 25 10 04 Payton Preslee XXX 2160p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            actorIds = listOf("actor_payton_preslee"),
            studioIds = listOf("studio_new_sensations"),
            assignedDate = parseDateToMillis("4 Oct 2025")
        ),

        // Scene 2: The Boss gLet Me Fuck All of My Coworkers: Freeuse On the Job
        LinkEntity(
            id = "scene_new_2",
            title = "The Boss gLet Me Fuck All of My Coworkers: Freeuse On the Job",
            coverImage = "https://stashdb.org/images/b6bb9c2b-4720-4bb7-b9f2-a67c70f4e82b",
            magnet = "magnet:?xt=urn:btih:822756fb476d60e2e8bf4a3dc9e7b5c2b463819e&dn=FreeUseFantasy 26 07 15 Jewelz Blu Adriana Maya And Luna Luxe XXX 1080p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            magnet4K = null,
            actorIds = listOf("actor_adriana_maya", "actor_jewelz_blu", "actor_luna_luxe"),
            studioIds = listOf("studio_freeuse_fantasy"),
            assignedDate = parseDateToMillis("15 Jul 2026")
        ),

        // Scene 3: Throuple's Counseling
        LinkEntity(
            id = "scene_new_3",
            title = "Throuple's Counseling",
            coverImage = "https://stashdb.org/images/72af7b6a-7574-4b25-858b-26b349688cad",
            magnet = "magnet:?xt=urn:btih:202df032f5d6026c5e90688b4d01e1050a1ba66e&dn=GirlsWay 26 07 26 Jewelz Blu Ella Reese Jazmin Black Throuples Counseling XXX 1080p MP4-VSEX [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            magnet4K = "magnet:?xt=urn:btih:52d5fa808f6dfbba5aa4b16e1ee07ea882f4f13c&dn=GirlsWay 26 07 26 Jewelz Blu Ella Reese Jazmin Black Throuples Counseling XXX 2160p MP4-VSEX [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            actorIds = listOf("actor_jazmin_black", "actor_ella_reese", "actor_jewelz_blu"),
            studioIds = listOf("studio_girlsway"),
            assignedDate = parseDateToMillis("26 Jul 2026")
        ),

        // Scene 4: SNOS-135
        LinkEntity(
            id = "scene_new_4",
            title = "SNOS-135",
            coverImage = "https://stashdb.org/images/8e598ea1-3882-4661-aa1f-641a6ca485e7",
            magnet = "magnet:?xt=urn:btih:65703470cbdc4e78c0f9b0f45d979aabd66945a3&dn=%2B%2B%2B%20%5BFHD%5D%20SNOS-135%20%E9%A1%94%E3%82%88%E3%82%8A%E3%83%87%E3%82%AB%E3%81%84%E8%83%B8%E3%81%AE%E3%81%9B%E3%81%84%E3%81%A7%E2%80%A6%E4%B9%B3%E3%81%A0%E3%81%91%E3%81%A7%E8%BA%AB%E3%83%90%E3%83%AC%E3%81%97%E3%81%A6%E3%81%97%E3%81%BE%E3%81%A3%E3%81%9F%E4%BA%BA%E5%A6%BB%E9%A2%A8%E4%BF%97%E5%AC%A2%E3%81%AE%E6%9C%AB%E8%B7%AF%E2%80%A6%E3%80%82%20%E6%97%A6%E9%82%A3%E4%B8%8D%E5%9C%A8%E3%81%AE%E9%96%93%E3%80%81%E3%81%94%E8%BF%91%E6%89%80%E3%81%95%E3%82%93%E5%BE%A1%E7%94%A8%E9%81%94%E3%81%AE%E3%83%A4%E3%83%A9%E3%82%8C%E6%94%BE%E9%A1%8CM%E3%82%AB%E3%83%83%E3%83%97%20%E3%81%BF%E3%81%AA%E3%81%BF%E7%BE%BD%E7%90%89&tr=http%3A%2F%2Fsukebei.tracker.wf%3A8888%2Fannounce&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=udp%3A%2F%2Fexodus.desync.com%3A6969%2Fannounce&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce",
            magnet4K = null,
            actorIds = listOf("actor_haru_minami"),
            studioIds = listOf("studio_s1"),
            assignedDate = parseDateToMillis("24 Mar 2026")
        ),

        // Scene 5: SNOS-027
        LinkEntity(
            id = "scene_new_5",
            title = "SNOS-027",
            coverImage = "https://stashdb.org/images/31af8c4c-33fe-40fd-a940-7ec4808f57e2",
            magnet = "magnet:?xt=urn:btih:30640444bbad4197e6ec422cbb9c028a92c8d04f&dn=%2B%2B%2B%20%5BFHD%5D%20SNOS-027%20%E5%B0%B1%E8%81%B7%E3%81%97%E3%81%9F%E6%B0%B4%E7%9D%80%E3%83%A1%E3%83%BC%E3%82%AB%E3%83%BC%E3%81%AF%E5%A5%B3%E6%80%A7%E7%A4%BE%E5%93%A1%E3%81%8C%E6%B0%B4%E7%9D%80%E5%A7%BF%E3%81%AE%E4%B8%96%E7%95%8C%E3%80%82%E5%8B%83%E8%B5%B7%E3%81%97%E3%81%A6%E3%82%82M%E3%82%AB%E3%83%83%E3%83%97%E3%81%AE%E6%95%99%E8%82%B2%E6%8B%85%E5%BD%93%E3%83%BB%E3%81%BF%E3%81%AA%E3%81%BF%E3%81%95%E3%82%93%E3%81%8C%E6%8A%9C%E3%81%84%E3%81%A6%E3%81%8F%E3%82%8C%E3%82%8B%E3%81%AE%E3%81%A7%E3%83%9C%E3%82%AF%E3%81%AE%E9%87%91%E7%8E%89%E3%81%AF%E6%AF%8E%E6%97%A5%E3%82%AB%E3%83%A9%E3%83%83%E3%83%9D%20%E3%81%BF%E3%81%AA%E3%81%BF%E7%BE%BD%E7%90%89&tr=http%3A%2F%2Fsukebei.tracker.wf%3A8888%2Fannounce&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=udp%3A%2F%2Fexodus.desync.com%3A6969%2Fannounce&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce",
            magnet4K = null,
            actorIds = listOf("actor_haru_minami"),
            studioIds = listOf("studio_s1"),
            assignedDate = parseDateToMillis("23 Dec 2025")
        ),

        // Scene 6: Mandi Lane - Step Sister Obsession (Part 1)
        LinkEntity(
            id = "scene_new_6",
            title = "Mandi Lane - Step Sister Obsession (Part 1)",
            coverImage = "https://stashdb.org/images/6110ebdc-0841-4795-af91-63a41f79f354",
            magnet = "magnet:?xt=urn:btih:07acb61c6d43514c16ede87c8020ad26b890cd5d&dn=TabooHeat 26 04 27 Mandi Love XXX 1080p MP4-Narcos [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            magnet4K = "magnet:?xt=urn:btih:be5775ebd2be1930ebceb7da903c5f8b400c69fb&dn=TabooHeat 26 04 27 Mandi Love XXX 2160p MP4-Narcos [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            actorIds = listOf("actor_mandi_love"),
            studioIds = listOf("studio_taboo_heat"),
            assignedDate = parseDateToMillis("27 Apr 2026")
        ),

        // Scene 7: Mandi Lane - Step Sister Obsession (Parts 2-3)
        LinkEntity(
            id = "scene_new_7",
            title = "Mandi Lane - Step Sister Obsession (Parts 2-3)",
            coverImage = "https://stashdb.org/images/83d32e04-d4a0-4c80-ae29-7197642fdd2d",
            magnet = "magnet:?xt=urn:btih:3c7f8841f464dfb810905907ed5a7219535092ff&dn=TabooHeat 26 04 28 Cory Chase Mandi Love XXX 1080p MP4-Narcos [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            magnet4K = "magnet:?xt=urn:btih:e195885f78ceaaab1ea6200dacbbc4daa616106b&dn=TabooHeat 26 04 28 Cory Chase Mandi Love XXX 2160p MP4-Narcos [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            actorIds = listOf("actor_cory_chase", "actor_mandi_love"),
            studioIds = listOf("studio_taboo_heat"),
            assignedDate = parseDateToMillis("28 Apr 2026")
        ),

        // Scene 8: Real Man Cock Gets Jill's Attention
        LinkEntity(
            id = "scene_new_8",
            title = "Real Man Cock Gets Jill's Attention",
            coverImage = "https://stashdb.org/images/5b0a6434-7e76-46da-b282-51ced79d13eb",
            magnet = "magnet:?xt=urn:btih:b593295512555f160e0c6738b43c212e4bad4348&dn=CuckoldSessions 26 04 04 Jill Taylor XXX 1080p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            magnet4K = "magnet:?xt=urn:btih:59f202ac44026899865cee06cefd1da9b8d93ef3&dn=CuckoldSessions 26 04 04 Jill Taylor XXX 2160p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            actorIds = listOf("actor_jill_taylor"),
            studioIds = listOf("studio_cuckold_sessions"),
            assignedDate = parseDateToMillis("4 Apr 2026")
        ),

        // Scene 9: Busty Payton Preslee Has A Hunger For Cock
        LinkEntity(
            id = "scene_new_9",
            title = "Busty Payton Preslee Has A Hunger For Cock",
            coverImage = "https://stashdb.org/images/9c67f245-74d5-4aaa-a49c-4a88904f7aa6",
            magnet = "magnet:?xt=urn:btih:53930494cc8cf2879d4d6315a66540a699b69df5&dn=Spizoo 26 08 19 Payton Preslee Busty Has A Hunger For Cock XXX 1080p MP4-P2P [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            magnet4K = "magnet:?xt=urn:btih:bec57dc69743ce258dc8204cabed18643dc1bdd0&dn=Spizoo 26 08 19 Payton Preslee Busty Has A Hunger For Cock XXX 2160p MP4-P2P [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            actorIds = listOf("actor_payton_preslee"),
            studioIds = listOf("studio_spizoo"),
            assignedDate = parseDateToMillis("19 Aug 2026")
        ),

        // Scene 10: My Stepsis is Too Sexy For a Towel
        LinkEntity(
            id = "scene_new_10",
            title = "My Stepsis is Too Sexy For a Towel",
            coverImage = "https://stashdb.org/images/40618a5e-3292-44bd-9990-8e8fd24fdfb0",
            magnet = "magnet:?xt=urn:btih:48108034d93090d20869dc98146f8b5b75b84b4a&dn=MyPervyFamily 26 05 02 Nia Bleu My Stepsis Is Too Sexy For A Towel XXX 1080p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            magnet4K = "magnet:?xt=urn:btih:6c1aacffa004e161e95d6f1eac633e9811228e3c&dn=MyPervyFamily 26 05 02 Nia Bleu My Stepsis Is Too Sexy For A Towel XXX 2160p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            actorIds = listOf("actor_nia_bleu"),
            studioIds = listOf("studio_my_pervy_family"),
            assignedDate = parseDateToMillis("2 May 2026")
        ),

        // Scene 11: Shared Wife Nia Bleu Delivers Mounds Of Joy
        LinkEntity(
            id = "scene_new_11",
            title = "Shared Wife Nia Bleu Delivers Mounds Of Joy",
            coverImage = "https://stashdb.org/images/5192f048-c50a-447d-8f97-c4a6c8786cf3",
            magnet = "magnet:?xt=urn:btih:dc269e9eb253542824d144d2555ea845f03324d2&dn=HotwifeXXX 26 04 29 Nia Bleu XXX 1080p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            magnet4K = "magnet:?xt=urn:btih:eac8321618f7eca6c5fc3614ee361fc3e0da0663&dn=HotwifeXXX 26 04 29 Nia Bleu XXX 2160p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            actorIds = listOf("actor_nia_bleu"),
            studioIds = listOf("studio_hot_wife_xxx"),
            assignedDate = parseDateToMillis("29 Apr 2026")
        ),

        // Scene 12: Birthday Boy's Boner
        LinkEntity(
            id = "scene_new_12",
            title = "Birthday Boy's Boner",
            coverImage = "https://stashdb.org/images/70de814c-5216-480a-8833-f8a9a456384b",
            magnet = "magnet:?xt=urn:btih:69f398a502cf665ba844e87aa0423344f258682d&dn=MommysBoy 26 04 01 Elana Bunnz And Nia Bleu XXX 1080p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            magnet4K = "magnet:?xt=urn:btih:a36e63e0853f2acb88e50e35af0b342598af659f&dn=MommysBoy 26 04 01 Elana Bunnz And Nia Bleu XXX 2160p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.bittor.pw:1337/announce&tr=udp://explodie.org:6969/announce&tr=udp://tracker.dler.org:6969/announce&tr=udp://open.demonii.com:1337/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://tracker.filemail.com:6969/announce&tr=udp://tracker.tryhackx.org:6969/announce&tr=udp://tracker.qu.ax:6969/announce&tr=udp://tracker.opentorrent.top:6969/announce&tr=udp://udp.tracker.projectk.org:23333/announce&tr=udp://martin-gebhardt.eu:6969/announce",
            actorIds = listOf("actor_elana_bunnz", "actor_nia_bleu"),
            studioIds = listOf("studio_mommys_boy"),
            assignedDate = parseDateToMillis("1 Apr 2026")
        ),

        // Scene 13: Speed Dicking
        LinkEntity(
            id = "scene_new_13",
            title = "Speed Dicking",
            coverImage = "https://stashdb.org/images/1191bdb9-924b-48a7-bd6f-30deca4450d9",
            magnet = "magnet:?xt=urn:btih:5c0b60b5c33ee922d31ef0f6b984446cdbaca2f5&dn=BrazzersExxtra",
            magnet4K = "magnet:?xt=urn:btih:cd687a2f687ba6102038cc776d92964071b61789&dn=BrazzersExxtra",
            actorIds = listOf("actor_luxe_lafox", "actor_nia_bleu", "actor_raissa_bellini", "actor_remy_woods"),
            studioIds = listOf("studio_brazzers_exxtra"),
            assignedDate = parseDateToMillis("14 Feb 2026")
        ),

        // Scene 14: Oily Booty Takes Two Dicks
        LinkEntity(
            id = "scene_new_14",
            title = "Oily Booty Takes Two Dicks",
            coverImage = "https://stashdb.org/images/4ebc8791-d30d-479f-94ba-cd57b0b43414",
            urlHD = "https://pixeldrain.com/u/pWiM34QD",
            url4K = null,
            magnet = "magnet:?xt=urn:btih:d6326c68595a7ab9a6454e21b6132f4b6a97455b&dn=AssParade 25 08 25 Nia Bleu XXX 1080p MP4-WRB [XC]&tr=udp://tracker.torrent.eu.org:451/announce&tr=",
            magnet4K = null,
            actorIds = listOf("actor_nia_bleu"),
            studioIds = listOf("studio_ass_parade"),
            assignedDate = parseDateToMillis("25 Aug 2025")
        )
    )
}
