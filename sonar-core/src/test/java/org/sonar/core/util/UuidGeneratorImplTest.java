/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
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
package org.sonar.core.util;

import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class UuidGeneratorImplTest {
  private UuidGeneratorImpl underTest = new UuidGeneratorImpl();

  @Test
  public void generate_returns_unique_values_without_common_initial_letter_given_more_than_one_milisecond_between_generate_calls() throws InterruptedException {
    Base64.Encoder encoder = Base64.getEncoder();
    int count = 30;
    Set<String> uuids = new HashSet<>(count);
    for (int i = 0; i < count; i++) {
      Thread.sleep(5);
      uuids.add(encoder.encodeToString(underTest.generate()));
    }
    assertThat(uuids).hasSize(count);

    Iterator<String> iterator = uuids.iterator();
    String firstUuid = iterator.next();
    String base = firstUuid.substring(0, firstUuid.length() - 4);
    for (int i = 1; i < count; i++) {
      assertThat(iterator.next()).describedAs("i=" + i).doesNotStartWith(base);
    }
  }

  @Test
  public void generate_from_FixedBase_returns_unique_values_where_only_last_4_later_letter_change() {
    Base64.Encoder encoder = Base64.getEncoder();
    int count = 100_000;
    Set<String> uuids = new HashSet<>(count);

    UuidGenerator.WithFixedBase withFixedBase = underTest.withFixedBase();
    for (int i = 0; i < count; i++) {
      uuids.add(encoder.encodeToString(withFixedBase.generate(i)));
    }
    assertThat(uuids).hasSize(count);

    Iterator<String> iterator = uuids.iterator();
    String firstUuid = iterator.next();
    String base = firstUuid.substring(0, firstUuid.length() - 4);
    while (iterator.hasNext()) {
      assertThat(iterator.next()).startsWith(base);
    }
  }
}
