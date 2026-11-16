/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * Writes a pinned-Cantonese transcript in Traditional characters, the way Hong Kong writes them
 * (BabApps item 117).
 *
 * Whisper's Cantonese output comes back in Simplified characters most of the time — measured with
 * `whisper-cli -l yue` on large-v3-turbo-q8_0 — while Cantonese readers expect Traditional. A prompt in
 * A Traditional initial prompt was tried and rejected: it turned the characters Traditional but moved
 * the wording to Mandarin (喝茶, 改了) and garbled the punctuation. So there is no prompt; this converts
 * the finished text instead. It is a plain lookup: the same text
 * always gives the same result, and text that is already Traditional, Latin or digits is untouched.
 *
 * It is the idea of OpenCC's `s2hk` (character table, then phrases for the characters that have
 * several Traditional forms) with a table sized for spoken language rather than all of Chinese. A
 * Simplified character that is not in the table is left as it is: a few stray Simplified letters
 * are better than a wrong one.
 */
object CantoneseScript {

    /** Converts Simplified Chinese characters in [text] to Hong Kong Traditional. */
    fun toTraditionalHk(text: String): String {
        if (text.none { it in CHARACTERS }) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val phrase = matchPhrase(text, i)
            if (phrase != null) {
                out.append(phrase.second)
                i += phrase.first
            } else {
                out.append(CHARACTERS[text[i]] ?: text[i])
                i++
            }
        }
        return out.toString()
    }

    /** [toTraditionalHk] on a segment and the lines it can be cut into. */
    fun toTraditionalHk(segment: TranscriptSegment): TranscriptSegment =
        segment.copy(
            text = toTraditionalHk(segment.text),
            parts = segment.parts.map { toTraditionalHk(it) },
        )

    private fun matchPhrase(text: String, at: Int): Pair<Int, String>? {
        for (len in MAX_PHRASE downTo 2) {
            if (at + len > text.length) continue
            val replacement = PHRASES[text.substring(at, at + len)]
            if (replacement != null) return len to replacement
        }
        return null
    }

    private const val MAX_PHRASE = 4

    /**
     * Words where a character has more than one Traditional form, or where the Cantonese reading
     * differs. Longest match wins. Checked before the character table.
     */
    private val PHRASES: Map<String, String> = phraseTable(
        // 系 is 係 ("is") in Cantonese, so it is converted everywhere except in these words.
        "关系=關係 联系=聯繫 体系=體系 系统=系統 系列=系列 系别=系別 太阳系=太陽系 银河系=銀河系 " +
            "头发=頭髮 理发=理髮 发型=髮型 " +
            "里面=裏面 这里=這裏 那里=那裏 哪里=哪裏 里边=裏邊 家里=家裏 心里=心裏 手里=手裏 " +
            "公里=公里 千里=千里 万里=萬里 海里=海里 " +
            "干净=乾淨 干燥=乾燥 饼干=餅乾 干杯=乾杯 干部=幹部 才干=才幹 干活=幹活 " +
            "面条=麵條 面包=麵包 面粉=麵粉 方便面=方便麵 汤面=湯麵 " +
            "一只=一隻 两只=兩隻 " +
            "台湾=臺灣 台北=臺北 台风=颱風 台灯=檯燈 桌台=桌檯 " +
            "重复=重複 复杂=複雜 回复=回覆 答复=答覆 " +
            "制作=製作 制造=製造 " +
            "钟头=鐘頭 时钟=時鐘 钟表=鐘錶 " +
            "冲凉=沖涼 冲洗=沖洗 " +
            "放松=放鬆 轻松=輕鬆 " +
            "什么=甚麼 怎么=怎麼 那么=那麼 这么=這麼 多么=多麼",
    )

    /**
     * Simplified → Hong Kong Traditional, one character each. Written as 2-character entries
     * (Simplified, then Traditional) so a misaligned pair cannot slip in unnoticed.
     */
    private val CHARACTERS: Map<Char, Char> = characterTable(
        "们們 个個 这這 来來 说說 话話 时時 间間 后後 会會 过過 还還 对對 没沒 现現 发發 里裏 " +
            "么麼 为為 样樣 吗嗎 呢呢 吧吧 啊啊 哪哪 嘛嘛 学學 国國 两兩 只只 开開 关關 门門 " +
            "问問 题題 听聽 见見 觉覺 记記 认認 识識 让讓 请請 谢謝 给給 买買 卖賣 钱錢 贵貴 " +
            "银銀 行行 车車 东東 西西 电電 脑腦 机機 网網 络絡 视視 频頻 听聽 语語 读讀 写寫 " +
            "书書 报報 纸紙 笔筆 师師 医醫 药藥 院院 医醫 护護 经經 济濟 业業 务務 员員 总總 " +
            "办辦 公公 室室 议議 讨討 论論 决決 定定 计計 划劃 设設 备備 产產 品品 质質 量量 " +
            "数數 据據 库庫 统統 系係 几幾 准準 软軟 件件 应應 该該 当當 点點 钟鐘 号號 码碼 线線 连連 " +
            "接接 通通 话話 发發 送送 收收 信信 息息 邮郵 件件 附附 图圖 片片 像像 头頭 脸臉 " +
            "眼眼 睛睛 耳耳 嘴嘴 脚腳 腿腿 身身 体體 爱愛 恋戀 欢歡 乐樂 喜喜 怒怒 哀哀 惊驚 " +
            "吓嚇 怕怕 烦煩 恼惱 愿願 望望 希希 帮幫 助助 谢謝 辞辭 职職 场場 厂廠 商商 贸貿 " +
            "易易 买買 卖賣 价價 格格 费費 税稅 账賬 单單 据據 币幣 汇匯 款款 转轉 帐賬 贷貸 " +
            "债債 赚賺 赔賠 亏虧 损損 赢贏 输輸 减減 增增 优優 惠惠 折折 扣扣 货貨 运運 输輸 " +
            "递遞 达達 购購 选選 择擇 换換 退退 修修 维維 护護 检檢 查查 测測 试試 验驗 证證 " +
            "明明 确確 实實 际際 况況 种種 类類 样樣 别別 另另 外外 边邊 远遠 近近 离離 难難 " +
            "容容 易易 简簡 单單 复復 杂雜 紧緊 张張 轻輕 重重 厚厚 薄薄 满滿 够夠 极極 很很 " +
            "非非 常常 太太 真真 刚剛 才才 已已 经經 将將 就就 却卻 但但 虽雖 然然 因因 为為 " +
            "于於 与與 从從 向向 往往 到到 把把 被被 让讓 使使 叫叫 想想 要要 能能 可可 以以 " +
            "应應 该該 须須 愿願 敢敢 肯肯 得得 了了 过過 吃吃 喝喝 饭飯 面面 汤湯 " +
            "饮飲 茶茶 酒酒 水水 菜菜 肉肉 鱼魚 鸡雞 鸭鴨 蛋蛋 饺餃 饼餅 粥粥 点點 心心 厨廚 " +
            "餐餐 厅廳 馆館 店店 铺鋪 场場 楼樓 层層 厕廁 卫衛 浴浴 厨廚 窗窗 灯燈 床床 桌桌 " +
            "椅椅 柜櫃 钥鑰 匙匙 锁鎖 门門 墙牆 屋屋 间間 层層 园園 区區 县縣 乡鄉 镇鎮 市市 " +
            "城城 乡鄉 村村 路路 街街 桥橋 铁鐵 站站 飞飛 船船 艇艇 驾駕 驶駛 开開 动動 带帶 " +
            "拿拿 放放 搬搬 抬抬 扔扔 抛拋 抢搶 夺奪 挣掙 摆擺 摇搖 摄攝 撑撐 担擔 拥擁 挤擠 " +
            "拦攔 拨撥 择擇 揽攬 损損 搂摟 摸摸 扩擴 扫掃 抚撫 抠摳 拟擬 拣揀 挂掛 挡擋 挥揮 " +
            "捡撿 换換 据據 掷擲 搀攙 报報 场場 块塊 坏壞 坚堅 垫墊 垒壘 墙牆 声聲 壳殼 处處 " +
            "备備 复復 够夠 头頭 夹夾 夸誇 奋奮 奖獎 妇婦 妈媽 姐姐 姓姓 娱娛 孙孫 宁寧 宝寶 " +
            "实實 审審 宪憲 宫宮 宽寬 宾賓 寻尋 导導 层層 届屆 属屬 岁歲 岛島 岗崗 峡峽 崭嶄 " +
            "币幣 帅帥 师師 帐帳 带帶 帮幫 干幹 广廣 庆慶 庄莊 应應 库庫 废廢 开開 异異 弃棄 " +
            "张張 弯彎 强強 归歸 录錄 彻徹 径徑 忆憶 忧憂 怀懷 态態 怜憐 总總 恳懇 恶惡 恼惱 " +
            "悬懸 惊驚 惯慣 愤憤 懒懶 战戰 戏戲 户戶 执執 扩擴 扰擾 扬揚 护護 报報 担擔 拥擁 " +
            "择擇 敌敵 数數 斗鬥 断斷 无無 旧舊 时時 显顯 晒曬 晓曉 晕暈 暂暫 术術 机機 杀殺 " +
            "杂雜 权權 条條 来來 杨楊 极極 构構 枪槍 柜櫃 标標 栋棟 树樹 样樣 桥橋 梦夢 检檢 " +
            "楼樓 欢歡 毕畢 气氣 汉漢 汤湯 沟溝 没沒 浓濃 济濟 涛濤 涨漲 渐漸 湾灣 满滿 滚滾 " +
            "灭滅 灵靈 灾災 炉爐 点點 烧燒 热熱 爷爺 牵牽 犹猶 独獨 狮獅 猎獵 猫貓 献獻 环環 " +
            "现現 琐瑣 电電 画畫 畅暢 疗療 疯瘋 痒癢 痴癡 皱皺 盘盤 监監 盖蓋 盘盤 睁睜 码碼 " +
            "础礎 确確 礼禮 祸禍 离離 私私 秀秀 种種 积積 称稱 稳穩 穷窮 窃竊 竞競 笔筆 笑笑 " +
            "笼籠 筑築 签簽 简簡 粮糧 纠糾 红紅 约約 级級 纪紀 纯純 纲綱 纳納 纵縱 纷紛 纸紙 " +
            "纹紋 线線 练練 组組 细細 织織 终終 绍紹 经經 结結 绕繞 绘繪 给給 络絡 绝絕 统統 " +
            "继繼 续續 绩績 绪緒 维維 综綜 绳繩 绿綠 缓緩 编編 缘緣 网網 罗羅 罚罰 职職 联聯 " +
            "聪聰 肃肅 肠腸 肤膚 肾腎 胀脹 胜勝 脏髒 脑腦 腊臘 脚腳 艰艱 艺藝 节節 芦蘆 苏蘇 " +
            "苦苦 荐薦 药藥 获獲 莲蓮 莹瑩 萝蘿 营營 萧蕭 著著 蓝藍 蔼藹 虏虜 虑慮 虫蟲 虽雖 " +
            "蚁蟻 衬襯 补補 衣衣 袜襪 裤褲 装裝 见見 观觀 规規 视視 览覽 觉覺 触觸 计計 订訂 " +
            "认認 讥譏 讨討 让讓 训訓 议議 讯訊 记記 讲講 讳諱 许許 论論 设設 访訪 证證 评評 " +
            "识識 诉訴 诊診 词詞 译譯 试試 诗詩 诚誠 话話 诞誕 诡詭 询詢 该該 详詳 语語 误誤 " +
            "说說 请請 诸諸 诺諾 读讀 课課 谁誰 调調 谅諒 谈談 谊誼 谋謀 谎謊 谓謂 谜謎 谢謝 " +
            "谣謠 谨謹 谱譜 贝貝 负負 贡貢 财財 责責 贤賢 败敗 货貨 质質 贩販 贪貪 贫貧 购購 " +
            "贯貫 贵貴 贷貸 贸貿 费費 贺賀 贼賊 赁賃 资資 赏賞 赐賜 赖賴 赚賺 赛賽 赞贊 赠贈 " +
            "赶趕 趋趨 跃躍 践踐 踪蹤 蹿躥 躏躪 轨軌 转轉 轮輪 软軟 轰轟 轻輕 载載 轿轎 较較 " +
            "辅輔 辆輛 辈輩 辉輝 辑輯 输輸 辖轄 辞辭 边邊 辽遼 达達 迁遷 过過 迈邁 运運 还還 " +
            "这這 进進 远遠 违違 连連 迟遲 选選 递遞 逻邏 遗遺 邓鄧 邮郵 邻鄰 郑鄭 酱醬 释釋 " +
            "里裏 针針 钉釘 钓釣 钢鋼 钦欽 钱錢 钻鑽 铁鐵 铃鈴 铅鉛 银銀 铜銅 铺鋪 链鏈 销銷 " +
            "锁鎖 锅鍋 锋鋒 错錯 键鍵 镇鎮 镜鏡 长長 门門 闪閃 闭閉 问問 闲閒 间間 闷悶 闹鬧 " +
            "阅閱 队隊 阳陽 阴陰 阵陣 阶階 际際 陆陸 陈陳 险險 随隨 隐隱 难難 雏雛 雾霧 静靜 " +
            "页頁 顶頂 项項 顺順 须須 顾顧 顿頓 预預 领領 频頻 颗顆 题題 颜顏 额額 风風 飘飄 " +
            "饥飢 饭飯 饮飲 饱飽 饿餓 馆館 驰馳 驱驅 驳駁 驴驢 驶駛 驻駐 驾駕 骂罵 骄驕 验驗 " +
            "骗騙 鲜鮮 鸟鳥 鸣鳴 鸭鴨 鸡雞 鹅鵝 鹤鶴 黄黃 齐齊 齿齒 龙龍 龟龜 档檔 栏欄 样樣 " +
            "严嚴 丽麗 乱亂 习習 书書 买買 争爭 亚亞 产產 亲親 亿億 仅僅 从從 仓倉 " +
            "仪儀 们們 价價 众眾 优優 伙夥 会會 伟偉 传傳 伤傷 伦倫 伪偽 体體 余餘 佣傭 侠俠 " +
            "侣侶 侥僥 侦偵 侧側 侨僑 俭儉 债債 倾傾 偿償 储儲 儿兒 兑兌 党黨 兰蘭 关關 兴興 " +
            "养養 兽獸 冈岡 册冊 写寫 军軍 农農 冯馮 冲衝 决決 况況 冻凍 净淨 凉涼 减減 凑湊 " +
            "凤鳳 凭憑 凯凱 击擊 凿鑿 刍芻 刘劉 则則 刚剛 创創 删刪 别別 刬剷 刹剎 剑劍 剥剝 " +
            "剧劇 劝勸 办辦 务務 劢勱 动動 励勵 劲勁 劳勞 势勢 勋勳 勐猛 勚勩 匀勻 匦匭 匮匱 " +
            "区區 医醫 华華 协協 单單 卖賣 卢盧 卫衛 却卻 厅廳 历歷 厉厲 压壓 厌厭 厕廁 厢廂 " +
            "县縣 参參 双雙 变變 叙敘 叠疊 号號 叹嘆 叽嘰 吓嚇 吕呂 吗嗎 吨噸 听聽 启啟 吴吳 " +
            "呐吶 呒嘸 呓囈 呕嘔 呖嚦 呗唄 员員 呙咼 呛嗆 呜嗚 咏詠 咙嚨 咛嚀 咝噝 响響 哑啞 " +
            "哒噠 哓嘵 哔嗶 哕噦 哗嘩 哙噲 哜嚌 哝噥 哟喲 唤喚 唛嘜 唠嘮 唢嗩 啬嗇 啭囀 啮嚙 " +
            "啸嘯 喷噴 喽嘍 嗫囁 嗳噯 嘘噓 嘤嚶 嘱囑 噜嚕 嚣囂 团團 园園 围圍 图圖 圆圓 圣聖 " +
            "垄壟 垅壠 垆壚 垒壘 垦墾 垩堊 垭埡 垲塏 埘塒 埙塤 埚堝 堑塹 堕墮 塆灣 墙牆 壮壯 " +
            "壶壺 寿壽 够夠 夺奪 奁奩 奂奐 奥奧 奸奸 妆妝 妇婦 妈媽 妩嫵 妪嫗 妫媯 姗姍 娄婁 " +
            "娅婭 娆嬈 娇嬌 娈孌 娲媧 娴嫻 婳嫿 婴嬰 婵嬋 婶嬸 媪媼 嫒嬡 嫔嬪 嫱嬙 嬷嬤 孙孫 " +
            "学學 孪孿 宁寧 宝寶 实實 宠寵 审審 宪憲 宫宮 宽寬 宾賓 寝寢 对對 寻尋 导導 寿壽 " +
            "将將 尔爾 尘塵 尝嘗 尧堯 尴尷 尸屍 层層 屃屭 屉屜 届屆 属屬 屡屢 屦屨 岁歲 岂豈 " +
            "岖嶇 岗崗 岘峴 岙嶴 岚嵐 岛島 岭嶺 岿巋 峄嶧 峡峽 峣嶢 峤嶠 峥崢 峦巒 崂嶗 " +
            "崃崍 崄嶮 崭嶄 嵘嶸 嵚嶔 嵝嶁 巅巔 巩鞏 巯巰 币幣 帅帥 师師 帏幃 帐帳 帜幟 带帶 " +
            "帧幀 帮幫 帱幬 帻幘 帼幗 幂冪 幞幞 并並 广廣 庄莊 庆慶 庐廬 庑廡 库庫 应應 庙廟 " +
            "庞龐 废廢 庼廎 廪廩 开開 异異 弃棄 弑弒 张張 弪弳 弯彎 弹彈 强強 归歸 当當 录錄 " +
            "彟彠 彦彥 彨彲 彻徹 径徑 徕徠 忆憶 忏懺 忧憂 忾愾 怀懷 态態 怂慫 怃憮 怄慪 怅悵 " +
            "怆愴 怜憐 总總 怼懟 怿懌 恋戀 恒恆 恳懇 恶惡 恸慟 恹懨 恺愷 恻惻 恼惱 恽惲 悦悅 " +
            "悫愨 悬懸 悭慳 悯憫 惊驚 惧懼 惨慘 惩懲 惫憊 愠慍 愤憤 愿願 慑懾 懑懣 懒懶 懔懍 " +
            "戆戇 戋戔 戏戲 戗戧 战戰 戬戩 户戶 扑撲 执執 扩擴 扪捫 扫掃 扬揚 扰擾 抚撫 抛拋 " +
            "抟摶 抠摳 抡掄 抢搶 护護 报報 担擔 拟擬 拢攏 拣揀 拥擁 拦攔 拧擰 拨撥 择擇 挚摯 " +
            "挛攣 挜掗 挝撾 挞撻 挟挾 挠撓 挡擋 挢撟 挣掙 挤擠 挥揮 挦撏 挽輓 捝挩 捞撈 损損 " +
            "捡撿 换換 捣搗 据據 掳擄 掴摑 掷擲 掸撣 掺摻 掼摜 揽攬 揿撳 搀攙 搁擱 搂摟 搅攪 " +
            "携攜 摄攝 摅攄 摆擺 摇搖 摈擯 摊攤 撄攖 撑撐 撵攆 撷擷 撸擼 撺攛 擞擻 攒攢 敌敵 " +
            "敛斂 数數 斋齋 斓斕 斗鬥 斩斬 断斷 无無 旧舊 时時 旷曠 旸暘 昙曇 昼晝 显顯 晋晉 " +
            "晒曬 晓曉 晔曄 晕暈 晖暉 暂暫 暧曖 术術 机機 杀殺 杂雜 权權 条條 来來 杨楊 " +
            "杩榪 极極 构構 枞樅 枢樞 枣棗 枥櫪 枧梘 枨棖 枪槍 枫楓 枭梟 柜櫃 柠檸 柽檉 栀梔 " +
            "栅柵 标標 栈棧 栉櫛 栊櫳 栋棟 栌櫨 栎櫟 栏欄 树樹 栖棲 样樣 栾欒 桊棬 桠椏 桡橈 " +
            "桢楨 档檔 桤榿 桥橋 桦樺 桧檜 桨槳 桩樁 梦夢 梼檮 梾棶 检檢 棂欞 椁槨 椟櫝 椠槧 " +
            "椤欏 椭橢 楼樓 榄欖 榇櫬 榈櫚 榉櫸 槚檟 槛檻 槟檳 槠櫧 横橫 樯檣 樱櫻 橥櫫 橱櫥 " +
            "橹櫓 橼櫞 檩檁 欢歡 欤歟 欧歐 歼殲 殁歿 殇殤 残殘 殒殞 殓殮 殚殫 殡殯 殴毆 毁毀 " +
            "毂轂 毕畢 毙斃 毡氈 毵毿 氇氌 气氣 氢氫 氩氬 氲氳 汇匯 汉漢 汤湯 汹洶 沟溝 没沒 " +
            "沣灃 沤漚 沥瀝 沦淪 沧滄 沨渢 沩溈 沪滬 沾沾 泞濘 泪淚 泶澩 泷瀧 泸瀘 泺濼 泻瀉 " +
            "泼潑 泽澤 泾涇 洁潔 洒灑 洼窪 浃浹 浅淺 浆漿 浇澆 浈湞 浉溮 浊濁 测測 浍澮 济濟 " +
            "浏瀏 浐滻 浑渾 浒滸 浓濃 浔潯 涛濤 涝澇 涞淶 涟漣 涠潿 涡渦 涣渙 涤滌 润潤 涧澗 " +
            "涨漲 涩澀 淀澱 渊淵 渌淥 渍漬 渎瀆 渐漸 渑澠 渔漁 渖瀋 渗滲 温溫 湾灣 湿濕 溃潰 " +
            "溅濺 溆漵 滞滯 满滿 滢瀅 滤濾 滥濫 滦灤 滨濱 滩灘 滪澦 漤灠 潆瀠 潇瀟 潋瀲 潍濰 " +
            "潜潛 潴瀦 澜瀾 濑瀨 濒瀕 灏灝 灭滅 灯燈 灵靈 灶竈 灾災 灿燦 炀煬 炉爐 炖燉 炜煒 " +
            "炝熗 炽熾 烁爍 烂爛 烃烴 烛燭 烟煙 烦煩 烧燒 烨燁 烩燴 烫燙 烬燼 热熱 焕煥 焖燜 " +
            "焘燾 煅煆 煳糊 熘溜 爱愛 爷爺 牍牘 牦氂 牵牽 牺犧 犊犢 状狀 犷獷 犹猶 狈狽 狝獮 " +
            "狞獰 独獨 狭狹 狮獅 狯獪 狰猙 狱獄 狲猻 猃獫 猎獵 猕獼 猡玀 猪豬 猫貓 猬蝟 献獻 " +
            "獭獺 玑璣 玚瑒 玛瑪 玮瑋 环環 现現 玱瑲 玺璽 珐琺 珑瓏 珰璫 珲琿 琎璡 琏璉 琐瑣 " +
            "琼瓊 瑶瑤 瑷璦 璎瓔 瓒瓚 瓯甌 电電 画畫 畅暢 畴疇 疖癤 疗療 疟瘧 疠癘 疡瘍 疬癧 " +
            "疮瘡 疯瘋 疱皰 疴痾 痈癰 痉痙 痒癢 痖瘂 痨癆 痪瘓 痫癇 瘅癉 瘆瘮 瘗瘞 瘘瘻 瘪癟 " +
            "瘫癱 瘾癮 瘿癭 癞癩 癣癬 癫癲 皑皚 皱皺 皲皸 盏盞 盐鹽 监監 盖蓋 盗盜 盘盤 眍瞘 " +
            "眦眥 眬矓 着著 睁睜 睐睞 睑瞼 瞒瞞 瞩矚 矫矯 矶磯 矾礬 矿礦 砀碭 码碼 砖磚 砗硨 " +
            "砚硯 砜碸 砺礪 砻礱 砾礫 础礎 硁硜 硕碩 硖硤 硗磽 硙磑 硚礄 确確 硷鹼 碍礙 碛磧 " +
            "碜磣 碱鹼 礼禮 祃禡 祎禕 祢禰 祯禎 祷禱 祸禍 禀稟 禄祿 禅禪 离離 秃禿 秆稈 种種 " +
            "积積 称稱 秽穢 秾穠 稆穭 税稅 稣穌 稳穩 穑穡 穷窮 窃竊 窍竅 窜竄 窝窩 窥窺 窦竇 " +
            "窭窶 竖豎 竞競 笃篤 笋筍 笔筆 笕筧 笺箋 笼籠 笾籩 筑築 筚篳 筛篩 筜簹 筝箏 筹籌 " +
            "签簽 简簡 箓籙 箦簀 箧篋 箨籜 箩籮 箪簞 箫簫 篑簣 篓簍 篮籃 篯籛 篱籬 簖籪 籁籟 " +
            "籴糴 类類 籼秈 粜糶 粝糲 粤粵 粪糞 粮糧 糁糝 糇餱 紧緊 絷縶 䌷紬 䌹絅 䌼絀 䍁縛 " +
            "纟糹 纠糾 纡紆 红紅 纣紂 纤纖 纥紇 约約 级級 纨紈 纩纊 纪紀 纫紉 纬緯 纭紜 纮紘 " +
            "纯純 纰紕 纱紗 纲綱 纳納 纴紝 纵縱 纶綸 纷紛 纸紙 纹紋 纺紡 纻紵 纼紖 纽紐 纾紓 " +
            "线線 绀紺 绁紲 绂紱 练練 组組 绅紳 细細 织織 终終 绉縐 绊絆 绋紼 绌絀 绍紹 绎繹 " +
            "经經 绐紿 绑綁 绒絨 结結 绔絝 绕繞 绖絰 绗絎 绘繪 给給 绚絢 绛絳 络絡 绝絕 绞絞 " +
            "统統 绠綆 绡綃 绢絹 绣繡 绤綌 绥綏 绦絛 继繼 绨綈 绩績 绪緒 绫綾 绬緓 续續 绮綺 " +
            "绯緋 绰綽 绱緔 绲緄 绳繩 维維 绵綿 绶綬 绷繃 绸綢 绹綯 绺綹 绻綣 综綜 绽綻 绾綰 " +
            "绿綠 缀綴 缁緇 缂緙 缃緗 缄緘 缅緬 缆纜 缇緹 缈緲 缉緝 缊縕 缋繢 缌緦 缍綞 缎緞 " +
            "缏緶 缑緱 缒縋 缓緩 缔締 缕縷 编編 缗緡 缘緣 缙縉 缚縛 缛縟 缜縝 缝縫 缞縗 缟縞 " +
            "缠纏 缡縭 缢縊 缣縑 缤繽 缥縹 缦縵 缧縲 缨纓 缩縮 缪繆 缫繅 缬纈 缭繚 缮繕 缯繒 " +
            "缰韁 缱繾 缲繰 缳繯 缴繳 缵纘 罂罌 网網 罗羅 罚罰 罢罷 罴羆 羁羈 羟羥 翘翹 耢耮 " +
            "耧耬 耸聳 耻恥 聂聶 聋聾 职職 聍聹 联聯 聩聵 聪聰 肃肅 肠腸 肤膚 肮骯 肴餚 肾腎 " +
            "肿腫 胀脹 胁脅 胆膽 胧朧 胨腖 胪臚 胫脛 胶膠 脉脈 脍膾 脏髒 脐臍 脑腦 脓膿 脔臠 " +
            "脚腳 脱脫 脶腡 脸臉 腊臘 腌醃 腘膕 腭齶 腻膩 腼靦 腽膃 腾騰 膑臏 臜臢 舆輿 舣艤 " +
            "舰艦 舱艙 舻艫 艰艱 艳艷 艺藝 节節 芈羋 芗薌 芜蕪 芦蘆 苁蓯 苇葦 苈藶 苋莧 苌萇 " +
            "苍蒼 苎苧 茎莖 茏蘢 茑蔦 茔塋 茕煢 茧繭 荆荊 荐薦 荚莢 荛蕘 荜蓽 荞蕎 荟薈 荠薺 " +
            "荡蕩 荣榮 荤葷 荥滎 荦犖 荧熒 荨蕁 荩藎 荪蓀 荫蔭 荬蕒 荭葒 荮葤 药藥 莅蒞 莱萊 " +
            "莲蓮 莳蒔 莴萵 莶薟 获獲 莸蕕 莹瑩 莺鶯 莼蓴 萚蘀 萝蘿 萤螢 营營 萦縈 萧蕭 萨薩 " +
            "葱蔥 蒇蕆 蒉蕢 蒋蔣 蒌蔞 蓝藍 蓟薊 蓠蘺 蓣蕷 蓥鎣 蓦驀 蔷薔 蔹蘞 蔺藺 蔼藹 蕲蘄 " +
            "蕴蘊 薮藪 藓蘚 虏虜 虑慮 虚虛 虫蟲 虬虯 虮蟣 虽雖 虾蝦 虿蠆 蚀蝕 蚁蟻 蚂螞 " +
            "蚕蠶 蚬蜆 蛊蠱 蛎蠣 蛏蟶 蛮蠻 蛰蟄 蛱蛺 蛲蟯 蛳螄 蛴蠐 蜕蛻 蜗蝸 蝇蠅 蝈蟈 蝉蟬 " +
            "蝼螻 蝾蠑 螀螿 螨蟎 蟏蠨 衅釁 衔銜 补補 衬襯 衮袞 袄襖 袅裊 袆褘 袜襪 袭襲 袯襏 " +
            "装裝 裆襠 裈褌 裢褳 裣襝 裤褲 裥襇 褛褸 褴襤 襁襁 见見 观觀 规規 觅覓 视視 觇覘 " +
            "览覽 觉覺 觊覬 觋覡 觌覿 觎覦 觏覯 觐覲 觑覷 觞觴 触觸 觯觶 訚誾 詟讋 誉譽 誊謄 " +
            "讠言 计計 订訂 讣訃 认認 讥譏 讦訐 讧訌 讨討 让讓 讪訕 讫訖 训訓 议議 讯訊 记記 " +
            "讲講 讳諱 讴謳 讵詎 讶訝 讷訥 许許 讹訛 论論 讼訟 讽諷 设設 访訪 诀訣 证證 诂詁 " +
            "诃訶 评評 诅詛 识識 诇詗 诈詐 诉訴 诊診 诋詆 诌謅 词詞 诎詘 诏詔 诐詖 译譯 诒詒 " +
            "诓誆 诔誄 试試 诖詿 诗詩 诘詰 诙詼 诚誠 诛誅 诜詵 话話 诞誕 诟詬 诠詮 诡詭 询詢 " +
            "诣詣 诤諍 该該 详詳 诧詫 诨諢 诩詡 诪譸 诫誡 诬誣 语語 诮誚 误誤 诰誥 诱誘 诲誨 " +
            "诳誑 说說 诵誦 诶誒 请請 诸諸 诹諏 诺諾 读讀 诼諑 诽誹 课課 诿諉 谀諛 谁誰 谂諗 " +
            "调調 谄諂 谅諒 谆諄 谇誶 谈談 谉讅 谊誼 谋謀 谌諶 谍諜 谎謊 谏諫 谐諧 谑謔 谒謁 " +
            "谓謂 谔諤 谕諭 谖諼 谗讒 谘諮 谙諳 谚諺 谛諦 谜謎 谝諞 谞諝 谟謨 谠讜 谡謖 谢謝 " +
            "谣謠 谤謗 谥謚 谦謙 谧謐 谨謹 谩謾 谪謫 谫譾 谬謬 谭譚 谮譖 谯譙 谰讕 谱譜 谲譎 " +
            "谳讞 谴譴 谵譫 谶讖",
    )

    /** "简=繁 ..." → {"简": "繁"}; the longer the key, the more specific the word. */
    private fun phraseTable(spec: String): Map<String, String> =
        spec.split(' ').filter { it.isNotEmpty() }.associate {
            val (simplified, traditional) = it.split('=')
            simplified to traditional
        }

    /** "简繁 简繁 ..." → {简: 繁}: each entry is exactly two characters. */
    private fun characterTable(spec: String): Map<Char, Char> =
        spec.split(' ').filter { it.isNotEmpty() }.associate {
            require(it.length == 2) { "Not a Simplified/Traditional pair: $it" }
            it[0] to it[1]
        }
}
