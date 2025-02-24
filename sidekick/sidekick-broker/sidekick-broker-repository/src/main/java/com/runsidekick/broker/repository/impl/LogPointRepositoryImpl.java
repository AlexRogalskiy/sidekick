package com.runsidekick.broker.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runsidekick.broker.error.CodedException;
import com.runsidekick.broker.model.ApplicationFilter;
import com.runsidekick.broker.model.LogPoint;
import com.runsidekick.broker.model.LogPointConfig;
import com.runsidekick.broker.repository.LogPointRepository;
import com.runsidekick.broker.util.ApplicationAwareProbeQueryFilter;
import com.runsidekick.broker.util.ProbeUtil;
import com.runsidekick.repository.BaseDBRepository;
import lombok.SneakyThrows;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.beans.PropertyDescriptor;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static com.runsidekick.broker.error.ErrorCodes.LOGPOINT_ALREADY_EXIST;
import static com.runsidekick.broker.util.LogPointUtil.getExpireCount;
import static com.runsidekick.broker.util.LogPointUtil.getExpireSecs;
import static com.runsidekick.broker.util.LogPointUtil.getExpireTimestamp;

/**
 * @author yasin.kalafat
 */
@Repository
public class LogPointRepositoryImpl extends BaseDBRepository implements LogPointRepository {

    private RowMapper<LogPoint> logPointRowMapper;
    private RowMapper<LogPointConfig> logPointConfigRowMapper;

    @PostConstruct
    public void init() {
        logPointRowMapper = createLogPointRowMapper(mapper);
        logPointConfigRowMapper = createLogPointConfigRowMapper(mapper);
    }

    private RowMapper<LogPointConfig> createLogPointConfigRowMapper(ObjectMapper mapper) {
        return new BeanPropertyRowMapper<LogPointConfig>(LogPointConfig.class) {
            @SneakyThrows
            @Override
            protected Object getColumnValue(ResultSet rs, int index, PropertyDescriptor pd) {
                if (pd.getName().equals("applicationFilters")) {
                    String applicationFiltersJson = rs.getString("application_filters");
                    if (StringUtils.isEmpty(applicationFiltersJson)) {
                        return null;
                    }
                    return mapper.readValue(
                            applicationFiltersJson,
                            new TypeReference<List<ApplicationFilter>>() {
                            });
                } else if (pd.getName().equals("webhookIds")) {
                    String webhookIds = rs.getString("webhook_ids");
                    if (StringUtils.isEmpty(webhookIds)) {
                        return null;
                    }
                    return mapper.readValue(
                            webhookIds,
                            new TypeReference<List<String>>() { });
                } else {
                    return super.getColumnValue(rs, index, pd);
                }
            }
        };
    }

    private RowMapper<LogPoint> createLogPointRowMapper(ObjectMapper mapper) {
        return new BeanPropertyRowMapper<LogPoint>(LogPoint.class) {
            @SneakyThrows
            @Override
            protected Object getColumnValue(ResultSet rs, int index, PropertyDescriptor pd) {
                if (pd.getName().equals("webhookIds")) {
                    String webhookIds = rs.getString("webhook_ids");
                    if (StringUtils.isEmpty(webhookIds)) {
                        return null;
                    }
                    return mapper.readValue(
                            webhookIds,
                            new TypeReference<List<String>>() { });
                } else {
                    return super.getColumnValue(rs, index, pd);
                }
            }
        };
    }

    @Override
    public LogPointConfig getLogPoint(String workspaceId, String logPointId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM LogPoint WHERE workspace_id = ? AND id = ?",
                    logPointConfigRowMapper,
                    workspaceId, logPointId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public LogPoint getLogPointById(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM LogPoint WHERE id = ?",
                    logPointRowMapper,
                    id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public void putLogPoint(String workspaceId, String userId, LogPointConfig logPointConfig, boolean fromApi)
            throws Exception {
        try {
            jdbcTemplate.update(
                    "INSERT INTO " +
                            "LogPoint(" +
                            "id, workspace_id, user_id, " +
                            "file_name, line_no, client, " +
                            "condition_expression, expire_secs, expire_count, " +
                            "file_hash, disabled, " +
                            "expire_timestamp, application_filters, log_expression, " +
                            "stdout_enabled, log_level, webhook_ids, from_api, predefined, probe_name) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ",
                    logPointConfig.getId(), workspaceId, userId,
                    logPointConfig.getFileName(), logPointConfig.getLineNo(), logPointConfig.getClient(),
                    logPointConfig.getConditionExpression(),
                    getExpireSecs(logPointConfig.getExpireSecs()),
                    getExpireCount(logPointConfig.getExpireCount()),
                    logPointConfig.getFileHash(), logPointConfig.isDisabled(),
                    getExpireTimestamp(logPointConfig.getExpireSecs()),
                    mapper.writeValueAsString(logPointConfig.getApplicationFilters()),
                    logPointConfig.getLogExpression(),
                    logPointConfig.isStdoutEnabled(),
                    logPointConfig.getLogLevel(),
                    mapper.writeValueAsString(logPointConfig.getWebhookIds()),
                    fromApi,
                    logPointConfig.isPredefined(), logPointConfig.getProbeName());
        } catch (DuplicateKeyException e) {
            throw new CodedException(
                    LOGPOINT_ALREADY_EXIST,
                    logPointConfig.getFileName(), logPointConfig.getLineNo(), logPointConfig.getClient());
        }
    }

    @Override
    public void removeLogPoint(String workspaceId, String userId, String logPointId) {
        jdbcTemplate.update(
                "DELETE FROM LogPoint WHERE workspace_id = ? AND user_id = ? AND id = ?",
                workspaceId, userId, logPointId);
    }

    @Override
    public long removeLogPoints(String workspaceId, String userId, List<String> logPointIds) {
        return jdbcTemplate.update(
                "DELETE FROM LogPoint WHERE workspace_id = ? AND user_id = ? AND id IN (?)",
                workspaceId, userId, logPointIds.toArray());
    }

    @Override
    public void enableDisableLogPoint(String workspaceId, String userId, String logPointId, boolean disable) {
        jdbcTemplate.update(
                "UPDATE LogPoint " +
                        "SET disabled=? " +
                        "WHERE workspace_id = ? AND user_id = ? AND id = ?",
                disable, workspaceId, userId, logPointId);
    }

    @Override
    @SneakyThrows
    public void updateLogPoint(String workspaceId, String userId, String logPointId, LogPoint logPoint) {
        jdbcTemplate.update(
                "UPDATE LogPoint " +
                        "SET " +
                        "condition_expression = ?, expire_secs = ?, expire_count = ?, " +
                        "expire_timestamp = ?, disabled = ?, log_expression = ?, stdout_enabled = ?, " +
                        "log_level = ?, webhook_ids = ?, predefined = ?, probe_name = ? " +
                        "WHERE workspace_id = ? AND user_id = ? AND id = ?",
                logPoint.getConditionExpression(),
                getExpireSecs(logPoint.getExpireSecs()), getExpireCount(logPoint.getExpireCount()),
                getExpireTimestamp(logPoint.getExpireSecs()), logPoint.isDisabled(), logPoint.getLogExpression(),
                logPoint.isStdoutEnabled(), logPoint.getLogLevel(), mapper.writeValueAsString(logPoint.getWebhookIds()),
                logPoint.isPredefined(), logPoint.getProbeName(),
                workspaceId, userId, logPointId);
    }

    @Override
    public List<LogPoint> listLogPoints(String workspaceId, String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM LogPoint WHERE workspace_id = ? AND user_id = ?",
                logPointRowMapper,
                workspaceId, userId);
    }

    @Override
    public Collection<LogPoint> queryLogPoints(String workspaceId, ApplicationFilter applicationFilter) {
        ApplicationAwareProbeQueryFilter queryFilter = ProbeUtil.probeQueryFilter(workspaceId, applicationFilter);

        Collection<LogPointConfig> logPointConfigs =
                jdbcTemplate.query(
                        "SELECT * FROM LogPoint WHERE workspace_id = ?" + queryFilter.getFiltersExpr().toString(),
                        logPointConfigRowMapper,
                        queryFilter.getArgs().toArray());
        return filterLogPoints(logPointConfigs, applicationFilter);
    }

    @Override
    public List<LogPoint> listPredefinedLogPoints(String workspaceId, String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM LogPoint WHERE workspace_id = ? AND user_id = ? AND predefined = 1",
                logPointRowMapper,
                workspaceId, userId);
    }

    private Collection<LogPoint> filterLogPoints(Collection<LogPointConfig> logPointConfigs,
                                                     ApplicationFilter filter) {
        Collection<LogPointConfig> filteredLogPoints = ProbeUtil.filterProbes(logPointConfigs, filter);
        return filteredLogPoints.stream().collect(Collectors.toList());
    }
}
