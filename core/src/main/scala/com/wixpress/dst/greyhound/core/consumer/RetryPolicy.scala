package com.wixpress.dst.greyhound.core.consumer

import java.time.{Instant, Duration => JavaDuration}
import java.util.concurrent.TimeUnit.MILLISECONDS

import com.wixpress.dst.greyhound.core.Serdes.StringSerde
import com.wixpress.dst.greyhound.core._
import com.wixpress.dst.greyhound.core.consumer.RetryAttempt.{RetryAttemptNumber, currentTime}
import com.wixpress.dst.greyhound.core.consumer.RetryDecision.{NoMoreRetries, RetryWith}
import com.wixpress.dst.greyhound.core.producer.ProducerRecord
import zio._
import zio.clock.Clock
import zio.duration.Duration

trait RetryPolicy[-R, -E] {
  def retryTopics(originalTopic: Topic): Set[Topic]

  def retryAttempt(topic: Topic, headers: Headers): URIO[R, Option[RetryAttempt]]

  def retryDecision(retryAttempt: Option[RetryAttempt],
                    record: ConsumerRecord[Chunk[Byte], Chunk[Byte]],
                    error: E): URIO[R, RetryDecision]
}

object RetryPolicy {

  // TODO this is Wix retry logic, maybe move to Wix adapter?
  def default[E](topic: Topic, group: Group, backoffs: Duration*): RetryPolicy[Clock, E] =
    new RetryPolicy[Clock, E] {
      private val topicPattern = """-retry-(\d+)$""".r.unanchored
      private val longDeserializer = StringSerde.mapM(string => Task(string.toLong))
      private val instantDeserializer = longDeserializer.map(Instant.ofEpochMilli)
      private val durationDeserializer = longDeserializer.map(Duration(_, MILLISECONDS))

      override def retryTopics(topic: Topic): Set[Topic] =
        backoffs.indices.foldLeft(Set(topic))((acc, attempt) => acc + s"$topic-$group-retry-$attempt")

      override def retryAttempt(topic: Topic, headers: Headers): URIO[Clock, Option[RetryAttempt]] = {
        val submittedHeader = headers.get(RetryHeader.Submitted, instantDeserializer)
        val backoffHeader = headers.get(RetryHeader.Backoff, durationDeserializer)
        (submittedHeader zipWith backoffHeader) { (submitted, backoff) =>
          val attempt = topic match {
            case topicPattern(attempt) => Some(attempt.toInt)
            case _ => None
          }
          for {
            a <- attempt
            s <- submitted
            b <- backoff
          } yield RetryAttempt(a, s, b)
        }.orElse(ZIO.none)
      }

      override def retryDecision(retryAttempt: Option[RetryAttempt],
                                 record: ConsumerRecord[Chunk[Byte], Chunk[Byte]],
                                 error: E): URIO[Clock, RetryDecision] =
        currentTime.map { now =>
          val nextRetryAttempt = retryAttempt.fold(0)(_.attempt + 1)
          backoffs.lift(nextRetryAttempt).map { backoff =>
            ProducerRecord(
              topic = s"$topic-$group-retry-$nextRetryAttempt",
              value = record.value,
              key = record.key,
              partition = None,
              headers = record.headers +
                (RetryHeader.Submitted -> toChunk(now.toEpochMilli)) +
                (RetryHeader.Backoff -> toChunk(backoff.toMillis)))
          }.fold[RetryDecision](NoMoreRetries)(RetryWith)
        }

      private def toChunk(long: Long): Chunk[Byte] =
        Chunk.fromArray(long.toString.getBytes)
    }

}

object RetryHeader {
  val Submitted = "submitTimestamp"
  val Backoff = "backOffTimeMs"
}

case class RetryAttempt(attempt: RetryAttemptNumber,
                        submittedAt: Instant,
                        backoff: Duration) {

  def sleep: URIO[Clock, Unit] = currentTime.flatMap { now =>
    val expiresAt = submittedAt.plus(backoff.asJava)
    val sleep = JavaDuration.between(now, expiresAt)
    clock.sleep(Duration.fromJava(sleep))
  }

}

object RetryAttempt {
  type RetryAttemptNumber = Int

  val currentTime = clock.currentTime(MILLISECONDS).map(Instant.ofEpochMilli)
}

sealed trait RetryDecision

object RetryDecision {
  case object NoMoreRetries extends RetryDecision
  case class RetryWith(record: ProducerRecord[Chunk[Byte], Chunk[Byte]]) extends RetryDecision
}
