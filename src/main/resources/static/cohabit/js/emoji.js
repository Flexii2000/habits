// Emoji-Auswahl hinter „+" in der Reaktionsleiste (Vertrag 2.7a, Web: eigenes
// Raster mit Suche). Kompakt gehalten: je Kategorie eine Zeichenkette, Eintraege
// durch „|" getrennt, zuerst das Emoji, dann deutsche Suchwoerter. Was hier
// fehlt (Hautfarben, seltene Zeichen), geht ueber das Suchfeld: ein getipptes
// oder eingefuegtes Emoji ist selbst ein Treffer - so ist jedes erreichbar.

const GROUPS = [
    ['Smileys', '😀', '😀 grinsen lachen freude|😃 lachen freude|😄 lachen strahlen|😁 grinsen zähne|😆 lachen|😅 schwitzen erleichtert puh|🤣 lachen rollen|😂 lachen tränen haha|🙂 lächeln|🙃 kopfüber ironie|😉 zwinkern|😊 lächeln erröten|😇 engel unschuldig|🥰 verliebt herzen|😍 verliebt herzaugen|🤩 begeistert sterne wow|😘 kuss|😗 kuss|😚 kuss|🥲 lächeln träne|😋 lecker|😛 zunge|😜 zunge zwinkern|🤪 verrückt|😝 zunge|🤑 geld|🤗 umarmung|🤭 kichern|🤫 pssst leise|🤔 nachdenken hmm|🫡 salut|🤐 mund zu|🤨 skeptisch|😐 neutral|😑 ausdruckslos|😶 sprachlos|🫥 unsichtbar|😏 grinsen|😒 genervt|🙄 augenrollen|😬 grimasse|😮‍💨 ausatmen puh|🤥 lügen|😌 erleichtert|😔 nachdenklich traurig|😪 müde|🤤 sabbern|😴 schlafen müde|😷 krank maske|🤒 krank fieber|🤕 verletzt|🤢 übel|🤮 kotzen|🤧 niesen erkältet|🥵 heiß schwitzen|🥶 kalt frieren|🥴 benommen|😵‍💫 schwindlig|🤯 explodieren umgehauen|🤠 cowboy|🥳 party feiern|🥸 verkleidet|😎 cool sonnenbrille|🤓 nerd|🧐 monokel prüfen|😕 verwirrt|🫤 skeptisch|😟 besorgt|🙁 traurig|😮 staunen oh|😯 staunen|😲 erstaunt|😳 verlegen|🥺 bitte flehen|🥹 gerührt|😦 erschrocken|😧 qual|😨 angst|😰 angst schwitzen|😥 enttäuscht|😢 weinen traurig|😭 heulen weinen|😱 schreien schock|😖 verwirrt|😣 durchhalten|😞 enttäuscht|😓 schwitzen|😩 erschöpft müde|😫 müde|🥱 gähnen müde|😤 schnauben triumph|😡 wütend|😠 wütend sauer|🤬 fluchen|😈 teufel|👿 teufel|💀 totenkopf tot|💩 haufen kacke|🤡 clown|👻 geist|👽 alien|🤖 roboter|😺 katze|😹 katze lachen|😻 katze verliebt|🙈 affe nichts sehen|🙉 affe nichts hören|🙊 affe nichts sagen|💋 kuss|💢 wut|💥 knall boom|💫 schwindlig|💦 schweiß tropfen|💨 schnell weg|💬 sprechblase|💭 gedanke|💤 schlafen zzz'],
    ['Menschen', '👋', '👋 winken hallo tschüss|🤚 hand|✋ hand stopp|🖖 vulkanier|👌 ok perfekt|🤌 italienisch|🤏 bisschen|✌️ peace sieg|🤞 daumen drücken glück|🫰 herz finger|🤟 liebe|🤘 rock|🤙 anrufen|👈 links|👉 rechts|👆 oben|👇 unten|☝️ zeigen eins|👍 daumen hoch gut ja|👎 daumen runter schlecht nein|✊ faust|👊 faust schlag|🤛 faust|🤜 faust|👏 klatschen applaus bravo|🙌 hände hoch jubel feiern|🫶 herz hände|👐 hände|🤲 hände|🤝 handschlag deal|🙏 bitte danke beten|✍️ schreiben|💅 nagellack|🤳 selfie|💪 stark muskel bizeps kraft|🦾 roboterarm|🦵 bein|🦶 fuß|👂 ohr|👃 nase|🧠 gehirn denken|🫀 herz organ|🫁 lunge atmen|🦷 zahn|👀 augen schauen|👁️ auge|👅 zunge|👄 mund|👶 baby|🧒 kind|👦 junge|👧 mädchen|🧑 person|👱 blond|👨 mann|🧔 bart|👩 frau|🧓 älter|👴 opa|👵 oma|🙅 nein|🙆 ok|💁 info|🙋 melden hand hoch|🙇 verbeugen|🤦 facepalm|🤷 schulterzucken egal|👮 polizei|🕵️ detektiv|💂 wache|🥷 ninja|👷 bauarbeiter|🤴 prinz|👸 prinzessin|🤵 anzug|👰 braut hochzeit|🤰 schwanger|👼 engel baby|🎅 weihnachtsmann|🦸 superheld|🦹 bösewicht|🧙 zauberer|🧚 fee|🧛 vampir|🧜 meerjungfrau|🧝 elfe|🧟 zombie|💆 massage|💇 haare friseur|🚶 gehen spazieren|🧍 stehen|🧎 knien|🏃 laufen rennen joggen|💃 tanzen|🕺 tanzen|👯 party|🧖 sauna|🧗 klettern bouldern|🤺 fechten|🏇 reiten|⛷️ ski|🏂 snowboard|🏌️ golf|🏄 surfen|🚣 rudern|🏊 schwimmen|⛹️ ball basketball|🏋️ gewichte heben training gym|🚴 radfahren fahrrad|🚵 mountainbike|🤸 rad turnen|🤼 ringen|🤽 wasserball|🤾 handball|🤹 jonglieren|🧘 yoga meditation|🛀 baden|🛌 schlafen bett|👭 freundinnen|👫 paar|👬 freunde|💏 kuss paar|💑 paar liebe|👪 familie|🗣️ sprechen|👥 gruppe|👣 fußspuren'],
    ['Natur', '🐶', '🐶 hund|🐱 katze|🐭 maus|🐹 hamster|🐰 hase|🦊 fuchs|🐻 bär|🐼 panda|🐻‍❄️ eisbär|🐨 koala|🐯 tiger|🦁 löwe|🐮 kuh|🐷 schwein|🐸 frosch|🐵 affe|🐔 huhn|🐧 pinguin|🐦 vogel|🐤 küken|🦆 ente|🦅 adler|🦉 eule|🦇 fledermaus|🐺 wolf|🐗 wildschwein|🐴 pferd|🦄 einhorn|🐝 biene|🐛 raupe|🦋 schmetterling|🐌 schnecke langsam|🐞 marienkäfer|🐜 ameise|🕷️ spinne|🐢 schildkröte langsam|🐍 schlange|🦎 eidechse|🦖 dino|🦕 dino|🐙 krake|🦑 tintenfisch|🦀 krabbe|🐡 kugelfisch|🐠 fisch|🐟 fisch|🐬 delfin|🐳 wal|🦈 hai|🐊 krokodil|🦓 zebra|🦍 gorilla|🐘 elefant|🦒 giraffe|🐪 kamel|🐑 schaf|🐐 ziege|🦌 hirsch|🐓 hahn|🦃 truthahn|🕊️ taube frieden|🦔 igel|🐿️ eichhörnchen|🦥 faultier|🐾 pfoten|🐉 drache|🌵 kaktus|🌲 baum tanne|🌳 baum|🌴 palme|🌱 keim wachsen|🌿 kraut|☘️ klee|🍀 glück kleeblatt|🍁 ahorn herbst|🍂 laub herbst|🍃 blätter wind|🍄 pilz|🌾 ähre|💐 blumenstrauß|🌷 tulpe|🌹 rose|🥀 verwelkt|🌺 blüte|🌸 kirschblüte|🌼 blume|🌻 sonnenblume|🌞 sonne|🌝 mond|🌛 mond|🌚 neumond|🌕 vollmond|🌙 mond nacht|🌍 erde welt|🪐 planet|⭐ stern|🌟 stern glänzen|✨ funkeln glitzer|⚡ blitz energie|☄️ komet|🔥 feuer flamme heiß|🌪️ tornado|🌈 regenbogen|☀️ sonne|🌤️ sonnig|⛅ wolkig|☁️ wolke|🌦️ schauer|🌧️ regen|⛈️ gewitter|❄️ schnee kalt|☃️ schneemann|⛄ schneemann|🌬️ wind|💧 tropfen wasser trinken|🌊 welle meer|🌫️ nebel'],
    ['Essen', '🍔', '🍏 apfel grün|🍎 apfel|🍐 birne|🍊 orange|🍋 zitrone|🍌 banane|🍉 melone|🍇 trauben|🍓 erdbeere|🫐 blaubeeren|🍒 kirschen|🍑 pfirsich|🥭 mango|🍍 ananas|🥥 kokos|🥝 kiwi|🍅 tomate|🍆 aubergine|🥑 avocado|🥦 brokkoli|🥬 salat|🥒 gurke|🌶️ chili scharf|🫑 paprika|🌽 mais|🥕 karotte möhre|🧄 knoblauch|🧅 zwiebel|🥔 kartoffel|🍠 süßkartoffel|🥐 croissant|🥯 bagel|🍞 brot|🥖 baguette|🥨 brezel|🧀 käse|🥚 ei|🍳 spiegelei braten kochen|🧈 butter|🥞 pfannkuchen|🧇 waffel|🥓 speck|🥩 steak fleisch|🍗 hähnchen|🍖 fleisch|🌭 hotdog|🍔 burger|🍟 pommes|🍕 pizza|🥪 sandwich|🥙 döner|🧆 falafel|🌮 taco|🌯 burrito|🥗 salat gesund|🥘 pfanne|🍝 nudeln pasta|🍜 suppe ramen|🍲 eintopf|🍛 curry|🍣 sushi|🍱 bento|🥟 teigtasche|🍤 garnele|🍙 reisball|🍚 reis|🥠 glückskeks|🍧 eis|🍨 eis|🍦 softeis|🥧 kuchen|🧁 cupcake|🍰 kuchen torte|🎂 geburtstag torte|🍮 pudding|🍭 lolli|🍬 bonbon süß|🍫 schokolade|🍿 popcorn|🍩 donut|🍪 keks|🥜 erdnuss|🍯 honig|🥛 milch|🍼 fläschchen|☕ kaffee|🍵 tee|🧃 saft|🥤 becher|🧋 bubble tea|🍺 bier|🍻 prost bier|🥂 anstoßen sekt|🍷 wein|🥃 whisky|🍸 cocktail|🍹 cocktail|🍾 sekt feiern|🧊 eiswürfel|🥄 löffel|🍴 besteck essen|🍽️ teller essen|🥣 schüssel müsli|🧂 salz'],
    ['Aktivität', '⚽', '⚽ fußball|🏀 basketball|🏈 football|⚾ baseball|🎾 tennis|🏐 volleyball|🏉 rugby|🥏 frisbee|🎱 billard|🏓 tischtennis|🏸 badminton|🏒 hockey|🏏 cricket|🥅 tor|⛳ golf|🪁 drachen|🏹 bogen|🎣 angeln|🤿 tauchen|🥊 boxen|🥋 kampfsport|🎽 laufshirt|🛹 skateboard|🛼 rollschuh|🛷 schlitten|⛸️ schlittschuh|🎿 ski|🏆 pokal sieg gewinnen|🥇 gold erster|🥈 silber zweiter|🥉 bronze dritter|🏅 medaille|🎖️ orden|🎗️ schleife|🎟️ ticket|🎪 zirkus|🎭 theater|🎨 kunst malen|🎬 film|🎤 mikrofon singen|🎧 musik kopfhörer|🎼 noten|🎹 klavier|🥁 schlagzeug|🎷 saxophon|🎺 trompete|🎸 gitarre|🎻 geige|🎲 würfel|♟️ schach|🎯 ziel treffer|🎳 bowling|🎮 spielen zocken|🧩 puzzle|🎉 party feiern konfetti|🎊 konfetti|🎈 ballon|🎁 geschenk|🎀 schleife|🎃 halloween|🎄 weihnachten baum|🎆 feuerwerk|🎇 wunderkerze|🧨 böller'],
    ['Reisen', '🚗', '🚗 auto|🚕 taxi|🚙 auto|🚌 bus|🏎️ rennwagen|🚓 polizei|🚑 krankenwagen|🚒 feuerwehr|🚚 lkw|🚜 traktor|🛵 roller|🏍️ motorrad|🚲 fahrrad rad|🛴 roller|🚨 alarm|✈️ flugzeug fliegen urlaub|🛫 abflug|🛬 landung|🚀 rakete start|🛸 ufo|🚁 hubschrauber|⛵ segeln|🚤 boot|🚢 schiff|⚓ anker|🚂 zug|🚆 zug|🚇 u-bahn|🚊 straßenbahn|🗺️ karte|🧭 kompass|🏔️ berg|⛰️ berg|🌋 vulkan|🏕️ camping|⛺ zelt|🏖️ strand urlaub|🏜️ wüste|🏝️ insel|🏞️ park|🏟️ stadion|🏗️ baustelle|🏠 haus zuhause|🏡 haus garten|🏢 büro|🏥 krankenhaus|🏦 bank|🏨 hotel|🏪 laden|🏫 schule|🏰 schloss|💒 hochzeit|🗼 turm|🗽 freiheit|⛪ kirche|⛲ brunnen|🌃 nacht stadt|🏙️ stadt|🌄 sonnenaufgang|🌅 sonnenaufgang|🌇 sonnenuntergang|🌉 brücke|🎠 karussell|🎡 riesenrad|🎢 achterbahn'],
    ['Objekte', '💡', '⌚ uhr|📱 handy|💻 laptop|⌨️ tastatur|🖥️ computer|🖱️ maus|💾 diskette|📷 kamera foto|📸 foto blitz|🎥 kamera film|📞 telefon|📺 fernseher|📻 radio|🎙️ mikrofon podcast|⏱️ stoppuhr|⏲️ timer|⏰ wecker aufstehen|⌛ sanduhr|⏳ sanduhr warten|🔋 batterie akku|🪫 akku leer|🔌 stecker|💡 idee glühbirne|🔦 taschenlampe|🕯️ kerze|💸 geld|💶 geld euro|💰 geldsack|💳 karte|💎 diamant|⚖️ waage|🧰 werkzeug|🔧 schraubenschlüssel|🔨 hammer|🛠️ werkzeug|🔩 schraube|⚙️ zahnrad|🧲 magnet|💣 bombe|🔪 messer|⚔️ schwerter|🛡️ schild|🚬 rauchen zigarette|🔮 kristallkugel|🔭 teleskop|🔬 mikroskop|🩹 pflaster|🩺 stethoskop arzt|💊 tablette medizin|💉 spritze impfung|🩸 blut|🧬 dna|🦠 virus|🌡️ thermometer fieber|🧹 besen putzen|🧺 wäsche|🧻 klopapier|🚽 toilette|🚿 dusche|🛁 badewanne|🧼 seife|🪥 zahnbürste zähne|🧽 schwamm|🧴 creme|🔑 schlüssel|🚪 tür|🪑 stuhl|🛋️ sofa|🛏️ bett schlafen|🧸 teddy|🖼️ bild|🛍️ einkaufen shopping|🛒 einkaufswagen|✉️ brief|📧 mail|📦 paket|📝 notiz schreiben|📄 dokument|📊 diagramm statistik|📈 steigend aufwärts|📉 fallend|📅 kalender datum|📋 klemmbrett|📁 ordner|📰 zeitung|📕 buch|📚 bücher lernen lesen|📖 lesen buch|🔖 lesezeichen|🔗 link|📎 büroklammer|📏 lineal|📌 pin|📍 ort pin|✂️ schere|🖊️ stift|✏️ bleistift|🖌️ pinsel|🔍 suchen lupe|🔒 schloss zu|🔓 offen|🎒 rucksack schule|👓 brille|🕶️ sonnenbrille|👔 hemd|👕 tshirt|👖 jeans|🧣 schal|🧤 handschuhe|🧥 jacke|🧦 socken|👗 kleid|👙 bikini|👜 tasche|👟 turnschuh laufschuh|👞 schuh|👑 krone könig|🎩 hut|🧢 kappe|💄 lippenstift|💍 ring|💼 aktentasche arbeit'],
    ['Symbole', '❤️', '❤️ herz liebe rot|🧡 herz orange|💛 herz gelb|💚 herz grün|💙 herz blau|🩵 herz hellblau|💜 herz lila|🤎 herz braun|🖤 herz schwarz|🩶 herz grau|🤍 herz weiß|🩷 herz rosa|💔 herz gebrochen|❤️‍🔥 herz feuer|❤️‍🩹 herz heilen|💕 herzen|💞 herzen|💓 herz schlag|💗 herz|💖 herz funkeln|💘 herz pfeil|💝 herz geschenk|💯 hundert perfekt|✅ erledigt haken|✔️ haken|☑️ haken|❌ kreuz nein falsch|➕ plus|➖ minus|✖️ mal|➗ geteilt|♾️ unendlich|❓ frage|❗ ausrufezeichen wichtig|‼️ ausrufezeichen|⁉️ frage|⚠️ warnung achtung|🚫 verboten|⛔ stopp|🆗 ok|🆒 cool|🆕 neu|🆓 gratis|🆙 up|🆘 hilfe sos|🔝 top|🔜 bald|🔙 zurück|🔚 ende|♻️ recycling|🔰 anfänger|0️⃣ null|1️⃣ eins|2️⃣ zwei|3️⃣ drei|4️⃣ vier|5️⃣ fünf|6️⃣ sechs|7️⃣ sieben|8️⃣ acht|9️⃣ neun|🔟 zehn|#️⃣ raute|▶️ abspielen|⏸️ pause|⏹️ stopp|⏩ schnell|🔁 wiederholen|⬆️ hoch|⬇️ runter|⬅️ links|➡️ rechts|🔄 neu|🎵 musik note|🎶 musik|🔴 rot|🟠 orange|🟡 gelb|🟢 grün|🔵 blau|🟣 lila|🟤 braun|⚫ schwarz|⚪ weiß|🟥 rot|🟩 grün|🟦 blau|🔶 orange|🔷 blau|🔺 dreieck|♈ widder|♉ stier|♊ zwillinge|♋ krebs|♌ löwe|♍ jungfrau|♎ waage|♏ skorpion|♐ schütze|♑ steinbock|♒ wassermann|♓ fische|☮️ frieden|☯️ yin yang|©️ copyright|™️ marke'],
    ['Flaggen', '🏁', '🏁 ziel flagge|🚩 flagge|🏳️ weiß|🏴 schwarz|🏳️‍🌈 regenbogen pride|🏴‍☠️ pirat|🇩🇪 deutschland|🇦🇹 österreich|🇨🇭 schweiz|🇪🇺 europa eu|🇬🇧 england uk|🇺🇸 usa amerika|🇫🇷 frankreich|🇮🇹 italien|🇪🇸 spanien|🇵🇹 portugal|🇳🇱 niederlande holland|🇧🇪 belgien|🇱🇺 luxemburg|🇩🇰 dänemark|🇸🇪 schweden|🇳🇴 norwegen|🇫🇮 finnland|🇮🇸 island|🇮🇪 irland|🇵🇱 polen|🇨🇿 tschechien|🇭🇺 ungarn|🇭🇷 kroatien|🇬🇷 griechenland|🇹🇷 türkei|🇺🇦 ukraine|🇨🇦 kanada|🇲🇽 mexiko|🇧🇷 brasilien|🇦🇷 argentinien|🇯🇵 japan|🇨🇳 china|🇰🇷 korea|🇮🇳 indien|🇹🇭 thailand|🇻🇳 vietnam|🇦🇺 australien|🇳🇿 neuseeland|🇿🇦 südafrika|🇪🇬 ägypten|🇲🇦 marokko|🇮🇱 israel'],
];

/** Kleinbuchstaben ohne Akzente: „lächeln" findet sich auch mit „lacheln". */
export function fold(text) {
    return String(text).toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
}

let parsed = null;

/** [{ name, icon, items: [{ emoji, words }] }], beim ersten Aufruf zerlegt. */
export function emojiGroups() {
    if (!parsed) {
        parsed = GROUPS.map(([name, groupIcon, data]) => ({
            name,
            icon: groupIcon,
            items: data.split('|').map(entry => {
                const space = entry.indexOf(' ');
                return space < 0
                    ? { emoji: entry, words: '' }
                    : { emoji: entry.slice(0, space), words: fold(entry.slice(space + 1)) };
            }),
        }));
    }
    return parsed;
}

/** Treffer fuer einen Suchbegriff: jedes Wort muss in den Suchwoertern vorkommen. */
export function searchEmoji(query) {
    const terms = fold(query).split(/\s+/).filter(Boolean);
    if (!terms.length) return [];
    const seen = new Set();
    const hits = [];
    for (const group of emojiGroups()) {
        for (const item of group.items) {
            if (!item.words || seen.has(item.emoji)) continue;
            if (terms.every(term => item.words.includes(term))) {
                seen.add(item.emoji);
                hits.push(item.emoji);
            }
        }
    }
    return hits;
}

// Dieselbe Regel wie im Dienst (Vertrag 2.7a): ein Graphem-Cluster mit einem
// Extended-Pictographic-Zeichen, einem Flaggenpaar oder einer Tastenkappe.
const EMOJI_RE = /\p{Extended_Pictographic}|\p{Regional_Indicator}{2}|⃣/u;

function graphemes(text) {
    if (typeof Intl !== 'undefined' && Intl.Segmenter) {
        return Array.from(new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text), s => s.segment);
    }
    return [text];
}

/** Die Emojis in einem getippten oder eingefuegten Text, jedes einmal. */
export function emojisIn(text) {
    const out = [];
    for (const g of graphemes(String(text).replace(/︎/g, '').replace(/\s+/g, ''))) {
        if (g.length <= 32 && EMOJI_RE.test(g) && !out.includes(g)) out.push(g);
    }
    return out;
}

/** Gleich bis auf die Darstellungs-Auswahl (❤ und ❤️ sind dasselbe). */
export function sameEmoji(a, b) {
    const strip = s => String(s || '').replace(/[︎️]/g, '');
    return strip(a) === strip(b);
}
