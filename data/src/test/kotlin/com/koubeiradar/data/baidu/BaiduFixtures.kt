package com.koubeiradar.data.baidu

/** One fully populated POI and one whose empty values arrive as `""`, both shaped like Baidu's `scope=2` replies. */
const val BAIDU_SAMPLE = """{"status":0,"message":"ok","total":2,"results":[
 {"name":"老王川菜馆(中关村店)","location":{"lat":39.991957,"lng":116.310003},"address":"中关村大街1号","province":"北京市","city":"北京市","area":"海淀区",
  "street_id":"abc","telephone":"(010)12345678,13800000000","detail":1,"uid":"U1",
  "detail_info":{"distance":356,"classified_poi_tag":"美食;中餐厅","type":"cater","detail_url":"http://api.map.baidu.com/place/detail?uid=U1&output=html","price":"58.0",
   "shop_hours":"10:00-22:00","overall_rating":"4.6","taste_rating":"4.5","comment_num":"1234","image_num":"20","favorite_num":"3"}},
 {"name":"某小吃","location":{"lat":39.99,"lng":116.31},"address":"","telephone":"","uid":"U2",
  "detail_info":{"distance":"412","classified_poi_tag":"美食;小吃快餐","price":"","overall_rating":"","comment_num":"","shop_hours":""}}]}"""

/** One POI object; [detail] is the body of its `detail_info` (omitted when null), [extra] is spliced in verbatim. */
fun baiduPoi(
    uid: String,
    name: String = "店$uid",
    distance: Int? = 100,
    location: String = """{"lat":39.99,"lng":116.31}""",
    detail: String? = "",
    extra: String = "",
): String = buildString {
    append("""{"name":"$name","location":$location,"address":"某路","uid":"$uid"""")
    if (distance != null) append(""","distance":"$distance"""")
    if (detail != null) append(""","detail_info":{$detail}""")
    if (extra.isNotEmpty()) append(",").append(extra)
    append("}")
}

fun baiduPageOf(vararg pois: String, total: Int? = pois.size): String =
    """{"status":0,"message":"ok",${total?.let { """"total":$it,""" }.orEmpty()}"results":[${pois.joinToString(",")}]}"""

/** A page of [count] unique POIs for 0-based [page] of 10, nearest first (each page starts where the previous one ended). */
fun baiduFullPage(page: Int, count: Int = 10, total: Int? = 150): String = baiduPageOf(
    *List(count) { i ->
        val n = page * 10 + i
        baiduPoi(uid = "P$n", distance = 100 + n * 10)
    }.toTypedArray(),
    total = total,
)

fun baiduError(status: Any, message: String = "SOME_ERROR"): String {
    val code = if (status is String) "\"$status\"" else status.toString()
    return """{"status":$code,"message":"$message"}"""
}
