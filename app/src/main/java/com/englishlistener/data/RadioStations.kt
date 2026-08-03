package com.englishlistener.data

/**
 * 预置电台列表 —— 公开直播源，无需授权即可播放
 */
object RadioStations {

    val all: List<RadioStation> = listOf(
        RadioStation(
            id = "cgtn_radio",
            name = "CGTN Radio",
            accent = Accent.CHINESE,
            streamUrl = "https://sk.cri.cn/am846.m3u8",
            description = "中国国际广播电台英语频道，发音清晰、语速适中，适合初中级学习者",
            category = Category.COMPREHENSIVE
        ),
        RadioStation(
            id = "cgtn_tv",
            name = "CGTN 英语新闻台",
            accent = Accent.CHINESE,
            streamUrl = "https://live.cgtn.com/1000/prog_index.m3u8",
            description = "CGTN 电视英语新闻 24 小时直播，带画面更有沉浸感",
            category = Category.NEWS
        ),
        RadioStation(
            id = "bbc_world",
            name = "BBC World Service",
            accent = Accent.BRITISH,
            streamUrl = "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service",
            description = "纯正英音全球新闻，语速快词汇量大，适合进阶学习",
            category = Category.NEWS
        ),
        RadioStation(
            id = "npr",
            name = "NPR",
            accent = Accent.AMERICAN,
            streamUrl = "https://npr-ice.streamguys1.com/live.mp3",
            description = "美国公共广播，美音标准，节目涵盖新闻、文化、科技",
            category = Category.COMPREHENSIVE
        ),
        RadioStation(
            id = "voa",
            name = "VOA Global English",
            accent = Accent.AMERICAN,
            streamUrl = "http://voa-28.akacast.akamaistream.net/7/54/322040/v1/ibb.akacast.akamaistream.net/voa-28",
            description = "美国之音全球英语，语速适中，经典英语学习素材",
            category = Category.NEWS
        ),
        RadioStation(
            id = "abc_news",
            name = "ABC News (AU)",
            accent = Accent.AUSTRALIAN,
            streamUrl = "https://abc-news-dmd-streams-1.akamaized.net/out/v1/abc83881886746b0802dc3e7ca2bc792/index.m3u8",
            description = "澳大利亚 ABC 新闻，澳音体验，拓宽听力多样性",
            category = Category.NEWS
        ),
        RadioStation(
            id = "aljazeera",
            name = "Al Jazeera English",
            accent = Accent.OTHER,
            streamUrl = "https://live-hls-web-aje-fa.thehlive.com/AJE/index.m3u8",
            description = "半岛电视台英语频道，中东视角国际新闻",
            category = Category.NEWS
        ),
        RadioStation(
            id = "trt_world",
            name = "TRT World",
            accent = Accent.OTHER,
            streamUrl = "https://tv-trtworld.medya.trt.com.tr/master.m3u8",
            description = "土耳其 TRT 英语新闻，多视角丰富听力素材",
            category = Category.NEWS
        )
    )
}
