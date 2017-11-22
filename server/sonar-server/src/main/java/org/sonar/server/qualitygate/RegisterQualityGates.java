/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.qualitygate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.picocontainer.Startable;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.core.util.stream.MoreCollectors;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.qualitygate.QualityGateConditionDao;
import org.sonar.db.qualitygate.QualityGateConditionDto;
import org.sonar.db.qualitygate.QualityGateDao;
import org.sonar.db.qualitygate.QualityGateDto;
import org.sonar.server.computation.task.projectanalysis.qualitymodel.RatingGrid;

import static java.util.Arrays.asList;
import static org.sonar.api.measures.CoreMetrics.NEW_COVERAGE_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_DUPLICATED_LINES_DENSITY_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_MAINTAINABILITY_RATING_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_RELIABILITY_RATING_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_SECURITY_RATING_KEY;
import static org.sonar.db.qualitygate.QualityGateConditionDto.OPERATOR_GREATER_THAN;
import static org.sonar.db.qualitygate.QualityGateConditionDto.OPERATOR_LESS_THAN;

public class RegisterQualityGates implements Startable {

  private static final Logger LOGGER = Loggers.get(RegisterQualityGates.class);

  private static final String BUILTIN_QUALITY_GATE = "Sonar way";
  private static final int LEAK_PERIOD = 1;
  private static final String A_RATING = Integer.toString(RatingGrid.Rating.A.getIndex());

  private static final List<QualityGateCondition> QUALITY_GATE_CONDITIONS = asList(
    new QualityGateCondition().setMetricKey(NEW_SECURITY_RATING_KEY).setOperator(OPERATOR_GREATER_THAN).setPeriod(LEAK_PERIOD).setErrorThreshold(A_RATING),
    new QualityGateCondition().setMetricKey(NEW_RELIABILITY_RATING_KEY).setOperator(OPERATOR_GREATER_THAN).setPeriod(LEAK_PERIOD).setErrorThreshold(A_RATING),
    new QualityGateCondition().setMetricKey(NEW_MAINTAINABILITY_RATING_KEY).setOperator(OPERATOR_GREATER_THAN).setPeriod(LEAK_PERIOD).setErrorThreshold(A_RATING),
    new QualityGateCondition().setMetricKey(NEW_COVERAGE_KEY).setOperator(OPERATOR_LESS_THAN).setPeriod(LEAK_PERIOD).setErrorThreshold("80"),
    new QualityGateCondition().setMetricKey(NEW_DUPLICATED_LINES_DENSITY_KEY).setOperator(OPERATOR_GREATER_THAN).setPeriod(LEAK_PERIOD).setErrorThreshold("3")
  );

  private final DbClient dbClient;
  private final QualityGateConditionsUpdater qualityGateConditionsUpdater;
  private final QualityGates qualityGates;
  private final QualityGateDao qualityGateDao;
  private final QualityGateConditionDao qualityGateConditionDao;

  public RegisterQualityGates(DbClient dbClient, QualityGateConditionsUpdater qualityGateConditionsUpdater, QualityGates qualityGates) {
    this.dbClient = dbClient;
    this.qualityGateConditionsUpdater = qualityGateConditionsUpdater;
    this.qualityGates = qualityGates;
    this.qualityGateDao = dbClient.qualityGateDao();
    this.qualityGateConditionDao = dbClient.gateConditionDao();
  }

  @Override
  public void start() {
    try (DbSession dbSession = dbClient.openSession(false)) {
      QualityGateDto builtin = qualityGateDao.selectByName(dbSession, BUILTIN_QUALITY_GATE);
      if (builtin == null) {
        LOGGER.info("Built in quality gate created [{}]", BUILTIN_QUALITY_GATE);
        builtin = createQualityGate(dbSession, BUILTIN_QUALITY_GATE);
        if (qualityGates.getDefault() == null) {
          qualityGates.setDefault(dbSession, builtin.getId());
        }
      }

      updateQualityConditionsIfRequired(dbSession, builtin);

      qualityGateDao.ensureOneBuiltInQualityGate(dbSession, BUILTIN_QUALITY_GATE);

      dbSession.commit();
    }
  }

  private void updateQualityConditionsIfRequired(DbSession dbSession, QualityGateDto builtin) {
    List<QualityGateCondition> qualityGateConditions = qualityGateConditionDao.selectForQualityGate(dbSession, builtin.getId())
      .stream()
      .map(dto -> {
        MetricDto metricDto = dbClient.metricDao().selectById(dbSession, dto.getMetricId());
        if (metricDto != null) {
          dto.setMetricKey(metricDto.getKey());
        }
        return QualityGateCondition.from(dto);
      })
      .collect(MoreCollectors.toList());

    // Delete old conditions
    List<QualityGateCondition> qgConditionsToBeDeleted = new ArrayList<>(qualityGateConditions);
    qgConditionsToBeDeleted.removeAll(QUALITY_GATE_CONDITIONS);
    qgConditionsToBeDeleted.stream()
      .forEach(qgc -> qualityGateConditionDao.delete(qgc.toQualityGateDto(builtin.getId()), dbSession));

    // Create new conditions
    List<QualityGateCondition> qgConditionsToBeCreated = new ArrayList<>(QUALITY_GATE_CONDITIONS);
    qgConditionsToBeCreated.removeAll(qualityGateConditions);
    qgConditionsToBeCreated.stream()
      .forEach(qgc ->
        qualityGateConditionsUpdater.createCondition(dbSession, builtin.getId(), qgc.getMetricKey(), qgc.getOperator(), qgc.getWarningThreshold(),
          qgc.getErrorThreshold(), qgc.getPeriod())
      );
  }

  @Override
  public void stop() {
    // do nothing
  }

  private QualityGateDto createQualityGate(DbSession dbSession, String name) {
    QualityGateDto qualityGate = new QualityGateDto()
      .setName(name)
      .setBuiltIn(true);
    return dbClient.qualityGateDao().insert(dbSession, qualityGate);
  }

  private static class QualityGateCondition {
    private Long id;
    private String metricKey;
    private Integer period;
    private String operator;
    private String warningThreshold;
    private String errorThreshold;

    public static QualityGateCondition from(QualityGateConditionDto qualityGateConditionDto) {
      return new QualityGateCondition()
        .setId(qualityGateConditionDto.getId())
        .setMetricKey(qualityGateConditionDto.getMetricKey())
        .setOperator(qualityGateConditionDto.getOperator())
        .setPeriod(qualityGateConditionDto.getPeriod())
        .setErrorThreshold(qualityGateConditionDto.getErrorThreshold())
        .setWarningThreshold(qualityGateConditionDto.getWarningThreshold());
    }

    @CheckForNull
    public Long getId() {
      return id;
    }

    public QualityGateCondition setId(Long id) {
      this.id = id;
      return this;
    }

    public String getMetricKey() {
      return metricKey;
    }

    public QualityGateCondition setMetricKey(String metricKey) {
      this.metricKey = metricKey;
      return this;
    }

    public Integer getPeriod() {
      return period;
    }

    public QualityGateCondition setPeriod(Integer period) {
      this.period = period;
      return this;
    }

    public String getOperator() {
      return operator;
    }

    public QualityGateCondition setOperator(String operator) {
      this.operator = operator;
      return this;
    }

    @CheckForNull
    public String getWarningThreshold() {
      return warningThreshold;
    }

    public QualityGateCondition setWarningThreshold(@Nullable String warningThreshold) {
      this.warningThreshold = warningThreshold;
      return this;
    }

    @CheckForNull
    public String getErrorThreshold() {
      return errorThreshold;
    }

    public QualityGateCondition setErrorThreshold(@Nullable String errorThreshold) {
      this.errorThreshold = errorThreshold;
      return this;
    }

    public QualityGateConditionDto toQualityGateDto(long qualityGateId) {
      return new QualityGateConditionDto()
        .setId(id)
        .setMetricKey(metricKey)
        .setOperator(operator)
        .setPeriod(period)
        .setErrorThreshold(errorThreshold)
        .setWarningThreshold(warningThreshold)
        .setQualityGateId(qualityGateId);
    }

    // id does not belongs to equals to be able to be compared with builtin
    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      QualityGateCondition that = (QualityGateCondition) o;
      return Objects.equals(metricKey, that.metricKey) &&
        Objects.equals(period, that.period) &&
        Objects.equals(operator, that.operator) &&
        Objects.equals(warningThreshold, that.warningThreshold) &&
        Objects.equals(errorThreshold, that.errorThreshold);
    }

    // id does not belongs to hashcode to be able to be compared with builtin
    @Override
    public int hashCode() {
      return Objects.hash(metricKey, period, operator, warningThreshold, errorThreshold);
    }
  }
}
