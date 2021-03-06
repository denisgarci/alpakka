/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.solr

import akka.NotUsed
import akka.stream.stage._
import akka.stream._
import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.apache.solr.client.solrj.response.UpdateResponse
import org.apache.solr.common.SolrInputDocument
import akka.stream.ActorAttributes.SupervisionStrategy
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.control.{NoStackTrace, NonFatal}

@deprecated(
  "you should use a specific incoming message case class: IncomingUpsertMessage/IncomingDeleteMessageByIds/IncomingDeleteMessageByQuery/IncomingAtomicUpdateMessage",
  "0.20"
)
object IncomingMessage {
  // Apply methods to use when not using passThrough
  def apply[T](source: T): IncomingMessage[T, NotUsed] =
    IncomingMessage(Upsert, None, None, None, None, Option(source), Map.empty, NotUsed)

  def apply[T](id: String): IncomingMessage[T, NotUsed] =
    IncomingMessage(DeleteByIds, None, Option(id), None, None, None, Map.empty, NotUsed)

  def apply[T](idField: String,
               idValue: String,
               routingFieldValue: Option[String],
               updates: Map[String, Map[String, Any]]): IncomingMessage[T, NotUsed] =
    IncomingMessage(AtomicUpdate, Option(idField), Option(idValue), routingFieldValue, None, None, updates, NotUsed)

  // Apply methods to use with passThrough
  def apply[T, C](source: T, passThrough: C): IncomingMessage[T, C] =
    IncomingMessage(Upsert, None, None, None, None, Option(source), Map.empty, passThrough)

  def apply[T, C](id: String, passThrough: C): IncomingMessage[T, C] =
    IncomingMessage(DeleteByIds, None, Option(id), None, None, None, Map.empty, passThrough)

  def apply[T, C](idField: String,
                  idValue: String,
                  routingFieldValue: Option[String],
                  updates: Map[String, Map[String, Any]],
                  passThrough: C): IncomingMessage[T, C] =
    IncomingMessage(AtomicUpdate, Option(idField), Option(idValue), routingFieldValue, None, None, updates, passThrough)

  // Java-api - without passThrough
  def create[T](source: T): IncomingMessage[T, NotUsed] =
    IncomingMessage(source)

  def create[T](id: String): IncomingMessage[T, NotUsed] =
    IncomingMessage(id)

  def create[T](idField: String,
                idValue: String,
                routingFieldValue: String,
                updates: java.util.Map[String, Map[String, Object]]): IncomingMessage[T, NotUsed] =
    IncomingMessage(idField, idValue, Option(routingFieldValue), updates.asScala.toMap)

  def create[T](idField: String,
                idValue: String,
                updates: java.util.Map[String, Map[String, Object]]): IncomingMessage[T, NotUsed] =
    IncomingMessage(idField, idValue, None, updates.asScala.toMap)

  // Java-api - with passThrough
  def create[T, C](source: T, passThrough: C): IncomingMessage[T, C] =
    IncomingMessage(source, passThrough)

  def create[T, C](id: String, passThrough: C): IncomingMessage[T, C] =
    IncomingMessage(id, passThrough)

  def create[T, C](idField: String,
                   idValue: String,
                   routingFieldValue: String,
                   updates: java.util.Map[String, Map[String, Object]],
                   passThrough: C): IncomingMessage[T, C] =
    IncomingMessage(idField, idValue, Option(routingFieldValue), updates.asScala.toMap, passThrough)

  def create[T, C](idField: String,
                   idValue: String,
                   updates: java.util.Map[String, Map[String, Object]],
                   passThrough: C): IncomingMessage[T, C] =
    IncomingMessage(idField, idValue, None, updates.asScala.toMap, passThrough)

  def asScalaUpdates(jupdates: java.util.Map[String, java.util.Map[String, Object]]): Map[String, Map[String, Any]] =
    jupdates.asScala.map {
      case (k, v: java.util.Map[String, Object]) =>
        (k, v.asScala.toMap)
    }.toMap
}

object IncomingUpsertMessage {
  // Apply method to use when not using passThrough
  def apply[T](source: T): IncomingMessage[T, NotUsed] =
    IncomingMessage(source)

  // Apply method to use when not using passThrough
  def apply[T, C](source: T, passThrough: C): IncomingMessage[T, C] =
    IncomingMessage(source, passThrough)

  // Java-api - without passThrough
  def create[T](source: T): IncomingMessage[T, NotUsed] =
    IncomingUpsertMessage[T](source)

  // Java-api - without passThrough
  def create[T, C](source: T, passThrough: C): IncomingMessage[T, C] =
    IncomingUpsertMessage[T, C](source, passThrough)
}

object IncomingDeleteMessageByIds {
  // Apply method to use when not using passThrough
  def apply[T](id: String): IncomingMessage[T, NotUsed] =
    IncomingMessage(id)

  def apply[T, C](id: String, passThrough: C): IncomingMessage[T, C] =
    IncomingMessage(id, passThrough)

  // Java-api - without passThrough
  def create[T](id: String): IncomingMessage[T, NotUsed] =
    IncomingDeleteMessageByIds[T](id)

  def create[T, C](id: String, passThrough: C): IncomingMessage[T, C] =
    IncomingDeleteMessageByIds[T, C](id, passThrough)
}

object IncomingDeleteMessageByQuery {
  // Apply method to use when not using passThrough
  def apply[T](query: String): IncomingMessage[T, NotUsed] =
    IncomingMessage(DeleteByQuery, None, None, None, Some(query), None, Map.empty, NotUsed)

  def apply[T, C](query: String, passThrough: C): IncomingMessage[T, C] =
    IncomingMessage(DeleteByQuery, None, None, None, Some(query), None, Map.empty, passThrough)

  // Java-api - without passThrough
  def create[T](query: String): IncomingMessage[T, NotUsed] =
    IncomingDeleteMessageByQuery[T](query)

  def create[T, C](query: String, passThrough: C): IncomingMessage[T, C] =
    IncomingDeleteMessageByQuery[T, C](query, passThrough)
}

object IncomingAtomicUpdateMessage {
  // Apply method to use when not using passThrough
  def apply[T](idField: String,
               idValue: String,
               routingFieldValue: Option[String],
               updates: Map[String, Map[String, Any]]): IncomingMessage[T, NotUsed] =
    IncomingMessage(idField, idValue, routingFieldValue, updates)

  def apply[T, C](idField: String,
                  idValue: String,
                  routingFieldValue: Option[String],
                  updates: Map[String, Map[String, Any]],
                  passThrough: C): IncomingMessage[T, C] =
    IncomingMessage(idField, idValue, routingFieldValue, updates, passThrough)

  // Java-api - without passThrough
  def create[T](idField: String,
                idValue: String,
                updates: java.util.Map[String, java.util.Map[String, Object]]): IncomingMessage[T, NotUsed] =
    IncomingAtomicUpdateMessage[T](idField, idValue, None, IncomingMessage.asScalaUpdates(updates))

  def create[T](idField: String,
                idValue: String,
                routingFieldValue: String,
                updates: java.util.Map[String, java.util.Map[String, Object]]): IncomingMessage[T, NotUsed] =
    IncomingAtomicUpdateMessage[T](idField, idValue, Option(routingFieldValue), IncomingMessage.asScalaUpdates(updates))

  def create[T, C](idField: String,
                   idValue: String,
                   updates: java.util.Map[String, java.util.Map[String, Object]],
                   passThrough: C): IncomingMessage[T, C] =
    IncomingAtomicUpdateMessage[T, C](idField, idValue, None, IncomingMessage.asScalaUpdates(updates), passThrough)

  def create[T, C](idField: String,
                   idValue: String,
                   routingFieldValue: String,
                   updates: java.util.Map[String, java.util.Map[String, Object]],
                   passThrough: C): IncomingMessage[T, C] =
    IncomingAtomicUpdateMessage[T, C](idField,
                                      idValue,
                                      Option(routingFieldValue),
                                      IncomingMessage.asScalaUpdates(updates),
                                      passThrough)
}

final case class IncomingMessage[T, C](operation: Operation,
                                       idField: Option[String],
                                       idFieldValue: Option[String],
                                       routingFieldValue: Option[String],
                                       query: Option[String],
                                       source: Option[T],
                                       updates: Map[String, Map[String, Any]],
                                       passThrough: C = NotUsed) {}

final case class IncomingMessageResult[T, C](idField: Option[String],
                                             idFieldValue: Option[String],
                                             routingFieldValue: Option[String],
                                             query: Option[String],
                                             source: Option[T],
                                             updates: Map[String, Map[String, Any]],
                                             passThrough: C,
                                             status: Int)

private[solr] final class SolrFlowStage[T, C](
    collection: String,
    client: SolrClient,
    settings: SolrUpdateSettings,
    messageBinder: T => SolrInputDocument
) extends GraphStage[FlowShape[Seq[IncomingMessage[T, C]], Seq[IncomingMessageResult[T, C]]]] {

  private val in = Inlet[Seq[IncomingMessage[T, C]]]("messages")
  private val out = Outlet[Seq[IncomingMessageResult[T, C]]]("result")
  override val shape = FlowShape(in, out)

  override protected def initialAttributes: Attributes =
    super.initialAttributes and Attributes(ActorAttributes.IODispatcher)

  override def createLogic(inheritedAttributes: Attributes): SolrFlowLogic[T, C] = {
    def decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider
    new SolrFlowLogic[T, C](decider, collection, client, in, out, shape, settings, messageBinder)
  }
}

sealed trait Operation
final object Upsert extends Operation
final object DeleteByIds extends Operation
final object DeleteByQuery extends Operation
final object AtomicUpdate extends Operation

private[solr] final class SolrFlowLogic[T, C](
    decider: Supervision.Decider,
    collection: String,
    client: SolrClient,
    in: Inlet[Seq[IncomingMessage[T, C]]],
    out: Outlet[Seq[IncomingMessageResult[T, C]]],
    shape: FlowShape[Seq[IncomingMessage[T, C]], Seq[IncomingMessageResult[T, C]]],
    settings: SolrUpdateSettings,
    messageBinder: T => SolrInputDocument
) extends GraphStageLogic(shape)
    with OutHandler
    with InHandler
    with StageLogging {

  setHandlers(in, out, this)

  override def onPull(): Unit =
    tryPull()

  override def onPush(): Unit = {
    val messagesIn = grab(in)
    try {
      sendBulkToSolr(messagesIn)
      tryPull()
    } catch {
      case NonFatal(ex) ⇒
        decider(ex) match {
          case Supervision.Stop ⇒ failStage(ex)
          case _ ⇒ tryPull() // for resume and restart strategies tryPull
        }
    }

  }

  override def preStart(): Unit =
    pull(in)

  override def onUpstreamFailure(ex: Throwable): Unit =
    handleFailure(ex)

  override def onUpstreamFinish(): Unit = handleSuccess

  private def tryPull(): Unit =
    if (!isClosed(in) && !hasBeenPulled(in)) {
      pull(in)
    }

  private def handleFailure(exc: Throwable): Unit = {
    log.warning(s"Received error from solr. Error: ${exc.toString}")
    failStage(exc)
  }

  private def handleResponse(args: (Seq[IncomingMessage[T, C]], Int)): Unit = {
    val (messages, status) = args
    log.debug(s"Handle the response with $status")
    val result = messages.map(
      m =>
        IncomingMessageResult(m.idField,
                              m.idFieldValue,
                              m.routingFieldValue,
                              m.query,
                              m.source,
                              m.updates,
                              m.passThrough,
                              status)
    )

    emit(out, result)
  }

  private def handleSuccess(): Unit =
    completeStage()

  private def updateBulkToSolr(messages: Seq[IncomingMessage[T, C]]): UpdateResponse = {
    val docs = messages.flatMap(_.source.map(messageBinder))

    if (log.isDebugEnabled) log.debug(s"Upsert $docs")
    client.add(collection, docs.asJava, settings.commitWithin)
  }

  private def atomicUpdateBulkToSolr(messages: Seq[IncomingMessage[T, C]]): UpdateResponse = {
    val docs = messages.map { message =>
      val doc = new SolrInputDocument()

      message.idField.foreach { idField =>
        message.idFieldValue.foreach { idFieldValue =>
          doc.addField(idField, idFieldValue)
        }
      }

      message.routingFieldValue.foreach { routingFieldValue =>
        val routingField = client match {
          case csc: CloudSolrClient =>
            Option(csc.getIdField)
          case _ => None
        }
        routingField.foreach { routingField =>
          message.idField.foreach { idField =>
            if (routingField != idField)
              doc.addField(routingField, routingFieldValue)
          }
        }
      }

      message.updates.foreach {
        case (field, updates) => {
          val jMap = updates.asInstanceOf[Map[String, Any]].asJava
          doc.addField(field, jMap)
        }
      }
      doc
    }
    if (log.isDebugEnabled) log.debug(s"Update atomically $docs")
    client.add(collection, docs.asJava, settings.commitWithin)
  }

  private def deleteBulkToSolrByIds(messages: Seq[IncomingMessage[T, C]]): UpdateResponse = {
    val docsIds = messages
      .filter { message =>
        message.operation == DeleteByIds && message.idFieldValue.isDefined
      }
      .map { message =>
        message.idFieldValue.get
      }
    if (log.isDebugEnabled) log.debug(s"Delete the ids $docsIds")
    client.deleteById(collection, docsIds.asJava, settings.commitWithin)
  }

  private def deleteEachByQuery(messages: Seq[IncomingMessage[T, C]]): UpdateResponse = {
    val responses = messages.map { message =>
      val query = message.query.get
      if (log.isDebugEnabled) log.debug(s"Delete by the query $query")
      client.deleteByQuery(collection, query, settings.commitWithin)
    }
    responses.find(_.getStatus != 0).getOrElse(responses.head)
  }

  private def sendBulkToSolr(messages: Seq[IncomingMessage[T, C]]): Unit = {

    @tailrec
    def send(toSend: Seq[IncomingMessage[T, C]]): UpdateResponse = {
      val operation = toSend.head.operation
      //Just take a subset of this operation
      val (current, remaining) = toSend.span { m =>
        m.operation == operation
      }
      //send this subset
      val response = operation match {
        case Upsert => updateBulkToSolr(current)
        case AtomicUpdate => atomicUpdateBulkToSolr(current)
        case DeleteByIds => deleteBulkToSolrByIds(current)
        case DeleteByQuery => deleteEachByQuery(current)
      }
      if (remaining.nonEmpty) {
        send(remaining)
      } else {
        response
      }
    }

    val response = if (messages.nonEmpty) send(messages) else new UpdateResponse
    handleResponse((messages, response.getStatus))
  }
}
