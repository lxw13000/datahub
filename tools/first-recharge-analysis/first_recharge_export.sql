-- MySQL 8.0
-- 首充分析数据导出 SQL。
-- 修改统计开始、结束时间后执行，并将结果导出为 .xlsx。
-- 结束时间采用开区间，避免遗漏结束日期内带时分秒的数据。

SET @statistics_start = '2026-09-14 11:00:00';
SET @statistics_end = '2026-10-01 00:00:00';

WITH first_recharge_data AS (
    SELECT
        r.user_id,

        -- Apple price_id 超过 Excel 的 15 位数字精度，必须以文本导出。
        CAST(r.price_id AS CHAR) AS price_id,

        p.channel_code,
        p.coin AS tier_coin,
        CAST(p.price AS DECIMAL(18, 2)) AS standard_amount_usd,

        -- 仅用于对账，不参与正式首充金额统计。
        r.money AS actual_money,
        r.coin AS actual_coin,

        r.create_time AS recharge_time,
        r.country_code,

        COUNT(*) OVER (
            PARTITION BY r.user_id
        ) AS successful_first_recharge_count,

        ROW_NUMBER() OVER (
            PARTITION BY r.user_id
            ORDER BY r.create_time ASC, CAST(r.price_id AS CHAR) ASC
        ) AS recharge_sequence
    FROM sano_wallet_recharge r
    INNER JOIN sano_config_recharge_price p
        ON p.id = r.price_id
       AND p.first_recharge_new = 1
    WHERE r.status = 2
      AND r.create_time >= @statistics_start
      AND r.create_time < @statistics_end
)
SELECT
    f.user_id,
    u.nick,

    f.channel_code,
    f.price_id,
    f.tier_coin,
    f.standard_amount_usd,

    f.actual_money,
    f.actual_coin,

    f.recharge_time,
    u.create_time AS register_time,

    TIMESTAMPDIFF(
        SECOND,
        u.create_time,
        f.recharge_time
    ) AS register_to_recharge_seconds,

    ROUND(
        TIMESTAMPDIFF(
            SECOND,
            u.create_time,
            f.recharge_time
        ) / 3600,
        3
    ) AS register_to_recharge_hours,

    f.country_code,
    COALESCE(c.country_name_remark, '未知国家') AS country_name,

    u.real_person,
    u.proxy_id,
    u.first_proxy_id,
    u.first_join_time,

    f.successful_first_recharge_count,
    f.recharge_sequence
FROM first_recharge_data f
LEFT JOIN sano_user u
    ON u.id = f.user_id
LEFT JOIN sano_config_country c
    ON c.country_code_small = f.country_code
ORDER BY
    f.recharge_time ASC,
    f.user_id ASC,
    f.recharge_sequence ASC;

