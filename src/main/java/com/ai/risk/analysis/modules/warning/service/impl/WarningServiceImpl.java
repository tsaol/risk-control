package com.ai.risk.analysis.modules.warning.service.impl;

import com.ai.risk.analysis.modules.warning.entity.unit.CallUnit;
import com.ai.risk.analysis.modules.warning.entity.po.Warning;
import com.ai.risk.analysis.modules.warning.mapper.WarningMapper;
import com.ai.risk.analysis.modules.warning.service.IWarningSV;
import com.ai.risk.analysis.modules.warning.util.HbaseOps;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.SQLException;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Steven
 * @since 2019-06-04
 */
@Slf4j
@Service
public class WarningServiceImpl extends ServiceImpl<WarningMapper, Warning> implements IWarningSV {

    /**
     * hbase生命周期
     */
    @Value("${spring.hbase.ttl}")
    private int ttl;

    /**
     * 阈值
     */
    @Value("${risk.threshold}")
    private int threshold;

    /**
     * 预警分析
     *
     * @param tableName
     * @param timestamp
     * @param list
     * @throws ParseException
     */
    @Override
    public void warning(String tableName, String timestamp, List<CallUnit> list) throws Exception {

        if (CollectionUtils.isEmpty(list)) {
            return;
        }

        Date date = DateUtils.parseDate(timestamp, "yyyyMMddHH");
        Date previousDate = DateUtils.addDays(date, -1);

        for (CallUnit callUnit : list) {

            int days = 0;
            int cnt = 0;
            int allDays = 1;

            String name = callUnit.getName();
            do {

                String rowKey = DateFormatUtils.format(previousDate, "yyyyMMddHH") + "-" + name;
                date = previousDate;
                Result result = selectByRowKey(tableName, rowKey);
                if (!result.isEmpty()) {
                    // 如果该条统计数据没有异常被预警，则计入统计平均值
                    if (!isWaringData(rowKey)) {
                        cnt += Integer.valueOf(Bytes.toString(result.getValue(HbaseOps.CF_BASE, HbaseOps.COL_CNT)));
                        days++;
                    }
                }
                allDays++;
                if (allDays >= ttl / 24 / 60 / 60) {
                    break;
                }
            } while (days < 30);

            long currCount = Long.valueOf(callUnit.getCount());
            long average = cnt / days;
            if (((currCount - average) / average) > threshold) {
                // 如果超过设定阈值则插入预警表
                insertWaring(callUnit);
            }
        }
    }

    private void insertWaring(CallUnit callUnit) throws SQLException, ClassNotFoundException {
        Warning warning = new Warning();
        warning.setName(callUnit.getName());
        warning.setCnt(callUnit.getCount());
        warning.setIsWarning("N");
        warning.setCreateDate(LocalDateTime.now());
        save(warning);
    }

    private boolean isWaringData(String name) throws SQLException, ClassNotFoundException {
        QueryWrapper<Warning> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(Warning::getName, name);
        int ret = count(queryWrapper);
        if (0 == ret) {
            return false;
        }
        return true;
    }

    private Result selectByRowKey(String tableName, String rowKey) throws IOException {
        HTable hTable = HbaseOps.getHbaseTable(tableName);
        Get get = new Get(Bytes.toBytes(rowKey));
        Result result = hTable.get(get);
        return result;
    }

}
