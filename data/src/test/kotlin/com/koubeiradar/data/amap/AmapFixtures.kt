package com.koubeiradar.data.amap

/** The response quoted in the task brief: one fully populated POI and one using Amap's `[]` for empty values. */
const val SAMPLE_RESPONSE = """{"status":"1","info":"OK","infocode":"10000","count":"2","pois":[
 {"id":"B0FFH1","name":"老王川菜馆(中关村店)","type":"餐饮服务;中餐厅;川菜馆","typecode":"050118","address":"中关村大街1号","location":"116.310003,39.991957","distance":"356",
  "business":{"business_area":"中关村","tel":"010-12345678;13800000000","tag":"水煮鱼,回锅肉","rating":"4.6","cost":"58.00","opentime_today":"10:00-22:00","opentime_week":"周一至周日 10:00-22:00","keytag":"川菜","rtag":[]}},
 {"id":"B0FFH2","name":"某小吃","type":"餐饮服务;快餐厅;快餐厅","address":[],"location":"116.31,39.99","distance":"412","business":{"rating":[],"cost":[],"tag":[],"tel":[]}}]}"""

/** One POI object; [extra] is spliced in verbatim (e.g. `"business":{...}`). */
fun poi(
    id: String,
    name: String = "店$id",
    distance: Int? = 100,
    location: String = "116.31,39.99",
    extra: String = "",
): String = buildString {
    append("""{"id":"$id","name":"$name","type":"餐饮服务;中餐厅;川菜馆","location":"$location"""")
    if (distance != null) append(""","distance":"$distance"""")
    if (extra.isNotEmpty()) append(",").append(extra)
    append("}")
}

fun pageOf(vararg pois: String): String =
    """{"status":"1","info":"OK","infocode":"10000","count":"${pois.size}","pois":[${pois.joinToString(",")}]}"""

/** A page of [count] unique POIs for 1-based [page], nearest first (each page starts where the previous one ended). */
fun fullPage(page: Int, count: Int = 25): String = pageOf(
    *List(count) { i ->
        val n = (page - 1) * 25 + i
        poi(id = "P$n", distance = 100 + n * 10)
    }.toTypedArray(),
)

fun errorBody(infocode: String, info: String = "SOME_ERROR"): String =
    """{"status":"0","info":"$info","infocode":"$infocode"}"""
