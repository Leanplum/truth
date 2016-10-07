/*
 * Copyright (c) 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.common.truth.extensions.proto;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertAbout;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.truth.FailureStrategy;
import com.google.common.truth.SubjectFactory;
import com.google.common.truth.Truth;
import com.google.common.truth.extensions.proto.MessageDifferencer.ReportType;
import com.google.common.truth.extensions.proto.MessageDifferencer.SpecificField;
import com.google.common.truth.extensions.proto.MessageDifferencer.StreamReporter;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Truth subjects for the full version of Protocol Buffers.
 *
 * <p>{@code ProtoSubject.assertThat(actual).isEqualTo(expected)} performs the same assertion as
 * {@code Truth.assertThat(actual).isEqualTo(expected)}, but with a better failure message.
 * By default, the assertions are strict with respect to repeated field order, missing fields, etc.
 * This behavior can be changed with the configuration methods on this subject, e.g.
 * {@code ProtoSubject.assertThat(actual).ignoringRepeatedFieldOrder().isEqualTo(expected)}.
 *
 * <p>Equality tests, and other methods, may yield slightly different behavior for versions 2 and 3
 * of Protocol Buffers. If testing protos of multiple versions, make sure you understand the
 * behaviors of default and unknown fields so you don't under or over test.
 *
 * @param <S> Subject class type.
 * @param <M> Message type.
 * @see ProtoFluentEquals
 */
public class ProtoSubject<S extends ProtoSubject<S, M>, M extends Message>
    extends LiteProtoSubject<S, M> implements ProtoFluentEquals {

  private boolean ignoreFieldAbsence = false;
  private boolean ignoreRepeatedFieldOrder = false;
  private boolean reportMismatchesOnly = false;
  private FieldScope fieldScope = FieldScopes.all();

  /**
   * Typed extension of {@link SubjectFactory}.
   *
   * <p>The existence of this class is necessary in order to satisfy the generic constraints of
   * {@link Truth#assertAbout(SubjectFactory)}, whilst also hiding the Untyped classes which are not
   * meant to be exposed.
   */
  public abstract static class Factory<S extends ProtoSubject<S, M>, M extends Message>
      extends LiteProtoSubject.Factory<S, M> {}

  /**
   * Returns a {@link SubjectFactory} for {@link Message} subjects which you can use to assert
   * things about Protobuf properties.
   */
  public static Factory<?, Message> protos() {
    return UntypedSubjectFactory.INSTANCE;
  }

  /** Returns a {@link Subject} using the assertion strategy on the provided {@link Message}. */
  public static ProtoSubject<?, Message> assertThat(@Nullable Message message) {
    return assertAbout(ProtoSubject.protos()).that(message);
  }

  protected ProtoSubject(FailureStrategy failureStrategy, M message) {
    super(failureStrategy, message);
  }

  @Override
  public ProtoFluentEquals ignoringFieldAbsence() {
    ignoreFieldAbsence = true;
    return this;
  }

  @Override
  public ProtoFluentEquals ignoringRepeatedFieldOrder() {
    ignoreRepeatedFieldOrder = true;
    return this;
  }

  @Override
  public ProtoFluentEquals withPartialScope(FieldScope fieldScope) {
    this.fieldScope = FieldScopeImpl.and(this.fieldScope, checkNotNull(fieldScope));
    return this;
  }

  @Override
  public ProtoFluentEquals ignoringFields(int... fieldNumbers) {
    this.fieldScope = fieldScope.ignoringFields(fieldNumbers);
    return this;
  }

  @Override
  public ProtoFluentEquals ignoringFieldDescriptors(FieldDescriptor... fieldDescriptors) {
    this.fieldScope = fieldScope.ignoringFieldDescriptors(fieldDescriptors);
    return this;
  }

  @Override
  public ProtoFluentEquals ignoringFieldScope(FieldScope fieldScope) {
    this.fieldScope =
        FieldScopeImpl.and(this.fieldScope, FieldScopeImpl.not(checkNotNull(fieldScope)));
    return this;
  }

  @Override
  public ProtoFluentEquals reportingMismatchesOnly() {
    reportMismatchesOnly = true;
    return this;
  }

  @Override
  public void isEqualTo(@Nullable Object expected) {
    if (getSubject() == null
        || expected == null
        || getSubject().getClass() != expected.getClass()) {
      super.isEqualTo(expected);
    } else {
      Reporter reporter = new Reporter();
      if (!makeDifferencer().compare((Message) expected, getSubject(), reporter)) {
        reporter.failEqual((Message) expected);
      }
    }
  }

  @Override
  public void isNotEqualTo(@Nullable Object expected) {
    if (getSubject() == null
        || expected == null
        || getSubject().getClass() != expected.getClass()) {
      super.isNotEqualTo(expected);
    } else {
      Reporter reporter = new Reporter();
      if (makeDifferencer().compare((Message) expected, getSubject(), reporter)) {
        reporter.failNotEqual((Message) expected);
      }
    }
  }

  @Override
  public void hasAllRequiredFields() {
    if (!getSubject().isInitialized()) {
      failWithRawMessage(
          "Not true that %s has all required fields set. Missing: %s",
          getTrimmedDisplaySubject(), getSubject().findInitializationErrors());
    }
  }

  private MessageDifferencer makeDifferencer() {
    return MessageDifferencer.newBuilder()
        .setMessageFieldComparison(
            ignoreFieldAbsence
                ? MessageDifferencer.MessageFieldComparison.EQUIVALENT
                : MessageDifferencer.MessageFieldComparison.EQUAL)
        .setRepeatedFieldComparison(
            ignoreRepeatedFieldOrder
                ? MessageDifferencer.RepeatedFieldComparison.AS_SET
                : MessageDifferencer.RepeatedFieldComparison.AS_LIST)
        .setReportMatches(!reportMismatchesOnly)
        .addIgnoreCriteria(fieldScope.toIgnoreCriteria(getSubject().getDescriptorForType()))
        .build();
  }

  /**
   * {@link MessageDifferencer.Reporter} implementation for reporting the results of {@link
   * #isEqualTo(Object)}.
   */
  private class Reporter implements MessageDifferencer.Reporter {
    private final List<ReporterRecord> records = new ArrayList<ReporterRecord>();
    private boolean anyFailures = false;
    private boolean anyNotices = false;

    @Override
    public void report(
        ReportType type, Message message1, Message message2, ImmutableList<SpecificField> path) {
      ReporterRecord record = ReporterRecord.of(type, message1, message2, path);
      anyFailures |= record.isFailure();
      anyNotices |= !record.isFailure();
      records.add(record);
    }

    void failEqual(Message expected) {
      StringBuilder rawMessage = new StringBuilder();
      rawMessage.append("Not true that ");
      if (ProtoSubject.this.internalCustomName() != null) {
        rawMessage.append(ProtoSubject.this.internalCustomName()).append(" compares equal. ");
      } else {
        rawMessage.append("messages compare equal. ");
      }

      StreamReporter streamReporter = new StreamReporter(rawMessage);
      if (anyFailures) {
        rawMessage.append("Differences were found:\n");
        for (ReporterRecord record : records) {
          if (record.isFailure()) {
            streamReporter.report(
                record.type(), record.message1(), record.message2(), record.path());
          }
        }

        if (anyNotices && !ProtoSubject.this.reportMismatchesOnly) {
          // Append the full report.
          rawMessage.append("\nFull diff:\n");
          for (ReporterRecord record : records) {
            streamReporter.report(
                record.type(), record.message1(), record.message2(), record.path());
          }
        }
      } else {
        rawMessage.append("No differences were reported.");
        if (!ProtoSubject.this.reportMismatchesOnly) {
          if (anyNotices) {
            rawMessage.append("\nFull diff:\n");
            for (ReporterRecord record : records) {
              streamReporter.report(
                  record.type(), record.message1(), record.message2(), record.path());
            }
          } else {
            // Shouldn't really happen, but it's better to print something than nothing if it does.
            rawMessage.append("\nActual:\n");
            rawMessage.append(TextFormat.printToString(getSubject()));
            rawMessage.append("Expected:\n");
            rawMessage.append(TextFormat.printToString(expected));
          }
        }
      }

      ProtoSubject.this.failWithRawMessage(rawMessage.toString());
    }

    void failNotEqual(Message expected) {
      StringBuilder rawMessage = new StringBuilder();
      rawMessage.append("Not true that ");
      if (ProtoSubject.this.internalCustomName() != null) {
        rawMessage.append(ProtoSubject.this.internalCustomName()).append(" compares not equal. ");
      } else {
        rawMessage.append("messages compare not equal. ");
      }

      if (!records.isEmpty() && !ProtoSubject.this.reportMismatchesOnly) {
        rawMessage.append("Only ignorable differences were found:\n");
        StreamReporter streamReporter = new StreamReporter(rawMessage);
        for (ReporterRecord record : records) {
          streamReporter.report(record.type(), record.message1(), record.message2(), record.path());
        }
      } else {
        rawMessage.append("No differences were found.");
        if (!ProtoSubject.this.reportMismatchesOnly) {
          rawMessage.append("\nActual:\n");
          rawMessage.append(TextFormat.printToString(getSubject()));
          rawMessage.append("Expected:\n");
          rawMessage.append(TextFormat.printToString(expected));
        }
      }

      ProtoSubject.this.failWithRawMessage(rawMessage.toString());
    }
  }

  @AutoValue
  abstract static class ReporterRecord {
    abstract ReportType type();

    abstract Message message1();

    abstract Message message2();

    abstract ImmutableList<SpecificField> path();

    // Whether this ReporterRecord indicates an actionable message difference.
    final boolean isFailure() {
      return type() == ReportType.ADDED
          || type() == ReportType.DELETED
          || type() == ReportType.MODIFIED;
    }

    static ReporterRecord of(
        ReportType type, Message message1, Message message2, ImmutableList<SpecificField> path) {
      return new AutoValue_ProtoSubject_ReporterRecord(type, message1, message2, path);
    }
  }

  private static final class UntypedSubject extends ProtoSubject<UntypedSubject, Message> {
    private UntypedSubject(FailureStrategy failureStrategy, @Nullable Message message) {
      super(failureStrategy, message);
    }
  }

  private static final class UntypedSubjectFactory extends Factory<UntypedSubject, Message> {
    private static final UntypedSubjectFactory INSTANCE = new UntypedSubjectFactory();

    @Override
    public UntypedSubject getSubject(FailureStrategy failureStrategy, @Nullable Message message) {
      return new UntypedSubject(failureStrategy, message);
    }
  }
}
