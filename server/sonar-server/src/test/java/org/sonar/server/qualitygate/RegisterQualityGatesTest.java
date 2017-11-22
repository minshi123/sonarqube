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
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.utils.System2;
import org.sonar.core.util.stream.MoreCollectors;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.qualitygate.QualityGateConditionDao;
import org.sonar.db.qualitygate.QualityGateConditionDto;
import org.sonar.db.qualitygate.QualityGateDao;
import org.sonar.db.qualitygate.QualityGateDto;
import org.sonar.server.computation.task.projectanalysis.qualitymodel.RatingGrid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.sonar.api.measures.CoreMetrics.NEW_COVERAGE_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_DUPLICATED_LINES_DENSITY_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_MAINTAINABILITY_RATING_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_RELIABILITY_RATING_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_SECURITY_RATING_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_SECURITY_REMEDIATION_EFFORT_KEY;
import static org.sonar.api.measures.Metric.ValueType.INT;
import static org.sonar.api.measures.Metric.ValueType.PERCENT;
import static org.sonar.db.metric.MetricTesting.newMetricDto;
import static org.sonar.db.qualitygate.QualityGateConditionDto.OPERATOR_GREATER_THAN;
import static org.sonar.db.qualitygate.QualityGateConditionDto.OPERATOR_LESS_THAN;

public class RegisterQualityGatesTest {

  @Rule
  public DbTester db = DbTester.create(System2.INSTANCE);

  private static final String A_RATING_VALUE = Integer.toString(RatingGrid.Rating.A.getIndex());
  private static final int LEAK_PERIOD = 1;

  private DbClient dbClient = db.getDbClient();
  private DbSession dbSession = db.getSession();

  private QualityGateDao qualityGateDao = dbClient.qualityGateDao();
  private QualityGateConditionDao gateConditionDao = dbClient.gateConditionDao();
  private QualityGateConditionsUpdater qualityGateConditionsUpdater = new QualityGateConditionsUpdater(dbClient);

  private QualityGates qualityGates = mock(QualityGates.class);
  private RegisterQualityGates underTest = new RegisterQualityGates(dbClient, qualityGateConditionsUpdater, qualityGates);

  @Before
  public void setup() {
    insertMetrics();
  }

  @After
  public void after() {
    underTest.stop();
    db.executeUpdateSql("delete from metrics");
    db.commit();
  }

  @Test
  public void register_default_gate() {
    underTest.start();

    verify(qualityGates).setDefault(any(DbSession.class), anyLong());
    checkBuiltInQualityGate();
  }

  @Test
  public void upgrade_empty_quality_gate() {
    QualityGateDto builtin = new QualityGateDto().setName("Sonar way").setBuiltIn(true);
    qualityGateDao.insert(dbSession, builtin);
    dbSession.commit();

    underTest.start();
    assertThat(qualityGateDao.selectAll(dbSession)).hasSize(1);
    checkBuiltInQualityGate();
  }

  @Test
  public void upgrade_should_remove_deleted_condition() {
    QualityGateDto builtin = new QualityGateDto().setName("Sonar way").setBuiltIn(true);
    qualityGateDao.insert(dbSession, builtin);

    createBuiltInConditions(builtin);

    // Add another condition
    qualityGateConditionsUpdater.createCondition(dbSession, builtin.getId(),
      NEW_SECURITY_REMEDIATION_EFFORT_KEY, OPERATOR_GREATER_THAN, null, "5", LEAK_PERIOD);

    dbSession.commit();

    underTest.start();
    assertThat(qualityGateDao.selectAll(dbSession)).hasSize(1);
    checkBuiltInQualityGate();
  }

  @Test
  public void upgrade_should_add_missing_condition() {
    QualityGateDto builtin = new QualityGateDto().setName("Sonar way").setBuiltIn(true);
    qualityGateDao.insert(dbSession, builtin);

    List<QualityGateConditionDto> builtInConditions = createBuiltInConditions(builtin);

    // Remove a condition
    QualityGateConditionDto conditionToBeDeleted = builtInConditions.get(new Random().nextInt(builtInConditions.size()));
    gateConditionDao.delete(conditionToBeDeleted, dbSession);

    dbSession.commit();

    underTest.start();
    assertThat(qualityGateDao.selectAll(dbSession)).hasSize(1);
    checkBuiltInQualityGate();
  }

  @Test
  public void ensure_only_one_built_in_quality_gate() {
    String qualityGateName = "IncorrectQualityGate";
    QualityGateDto builtin = new QualityGateDto().setName(qualityGateName).setBuiltIn(true);
    qualityGateDao.insert(dbSession, builtin);
    dbSession.commit();

    underTest.start();

    QualityGateDto oldQualityGate = qualityGateDao.selectByName(dbSession, qualityGateName);
    assertThat(oldQualityGate).isNotNull();
    assertThat(oldQualityGate.isBuiltIn()).isFalse();

    List<QualityGateDto> allBuiltInQualityProfiles = dbClient.qualityGateDao()
      .selectAll(dbSession)
      .stream()
      .filter(QualityGateDto::isBuiltIn)
      .collect(MoreCollectors.toList());
    assertThat(allBuiltInQualityProfiles)
      .extracting("name")
      .containsExactly("Sonar way");
  }

  private void insertMetrics() {
    dbClient.metricDao().insert(dbSession, newMetricDto().setKey(NEW_RELIABILITY_RATING_KEY).setValueType(INT.name()).setHidden(false));
    dbClient.metricDao().insert(dbSession, newMetricDto().setKey(NEW_SECURITY_RATING_KEY).setValueType(INT.name()).setHidden(false));
    dbClient.metricDao().insert(dbSession, newMetricDto().setKey(NEW_SECURITY_REMEDIATION_EFFORT_KEY).setValueType(INT.name()).setHidden(false));
    dbClient.metricDao().insert(dbSession, newMetricDto().setKey(NEW_MAINTAINABILITY_RATING_KEY).setValueType(PERCENT.name()).setHidden(false));
    dbClient.metricDao().insert(dbSession, newMetricDto().setKey(NEW_COVERAGE_KEY).setValueType(PERCENT.name()).setHidden(false));
    dbClient.metricDao().insert(dbSession, newMetricDto().setKey(NEW_DUPLICATED_LINES_DENSITY_KEY).setValueType(PERCENT.name()).setHidden(false));
    dbSession.commit();
  }

  private void checkBuiltInQualityGate() {
    MetricDto newReliability = dbClient.metricDao().selectByKey(dbSession, NEW_RELIABILITY_RATING_KEY);
    MetricDto newSecurity = dbClient.metricDao().selectByKey(dbSession, NEW_SECURITY_RATING_KEY);
    MetricDto newMaintainability = dbClient.metricDao().selectByKey(dbSession, NEW_MAINTAINABILITY_RATING_KEY);
    MetricDto newCoverage = dbClient.metricDao().selectByKey(dbSession, NEW_COVERAGE_KEY);
    MetricDto newDuplication = dbClient.metricDao().selectByKey(dbSession, NEW_DUPLICATED_LINES_DENSITY_KEY);

    QualityGateDto qualityGateDto = dbClient.qualityGateDao().selectByName(dbSession, "Sonar way");
    assertThat(qualityGateDto).isNotNull();
    assertThat(qualityGateDto.isBuiltIn()).isTrue();
    assertThat(gateConditionDao.selectForQualityGate(dbSession, qualityGateDto.getId()))
      .extracting(QualityGateConditionDto::getMetricId, QualityGateConditionDto::getOperator, QualityGateConditionDto::getWarningThreshold,
        QualityGateConditionDto::getErrorThreshold, QualityGateConditionDto::getPeriod)
      .containsOnly(
        tuple(newReliability.getId().longValue(), OPERATOR_GREATER_THAN, null, "1", 1),
        tuple(newSecurity.getId().longValue(), OPERATOR_GREATER_THAN, null, "1", 1),
        tuple(newMaintainability.getId().longValue(), OPERATOR_GREATER_THAN, null, "1", 1),
        tuple(newCoverage.getId().longValue(), OPERATOR_LESS_THAN, null, "80", 1),
        tuple(newDuplication.getId().longValue(), OPERATOR_GREATER_THAN, null, "3", 1));
  }

  private List<QualityGateConditionDto> createBuiltInConditions(QualityGateDto builtin) {
    List<QualityGateConditionDto> conditions = new ArrayList<>();

    conditions.add(qualityGateConditionsUpdater.createCondition(dbSession, builtin.getId(),
      NEW_SECURITY_RATING_KEY, OPERATOR_GREATER_THAN, null, A_RATING_VALUE, LEAK_PERIOD));
    conditions.add(qualityGateConditionsUpdater.createCondition(dbSession, builtin.getId(),
      NEW_RELIABILITY_RATING_KEY, OPERATOR_GREATER_THAN, null, A_RATING_VALUE, LEAK_PERIOD));
    conditions.add(qualityGateConditionsUpdater.createCondition(dbSession, builtin.getId(),
      NEW_MAINTAINABILITY_RATING_KEY, OPERATOR_GREATER_THAN, null, A_RATING_VALUE, LEAK_PERIOD));
    conditions.add(qualityGateConditionsUpdater.createCondition(dbSession, builtin.getId(),
      NEW_COVERAGE_KEY, OPERATOR_LESS_THAN, null, "80", LEAK_PERIOD));
    conditions.add(qualityGateConditionsUpdater.createCondition(dbSession, builtin.getId(),
      NEW_DUPLICATED_LINES_DENSITY_KEY, OPERATOR_GREATER_THAN, null, "3", LEAK_PERIOD));

    return conditions;
  }
}
