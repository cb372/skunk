// Copyright (c) 2018 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package skunk.net.protocol

import cats.MonadError
import cats.implicits._
import skunk.exception.{ UnexpectedRowsException, ColumnAlignmentException, NoDataException }
import skunk.net.MessageSocket
import skunk.net.Protocol.StatementId
import skunk.net.message.{ Describe => DescribeMessage, _ }
import skunk.util.Typer
import skunk.exception.UnknownOidException
import skunk.data.TypedRowDescription

trait Describe[F[_]] {
  def apply(cmd: skunk.Command[_], id: StatementId, ty: Typer): F[Unit]
  def apply[A](query: skunk.Query[_, A], id: StatementId, ty: Typer): F[TypedRowDescription]
}

object Describe {

  def apply[F[_]: MonadError[?[_], Throwable]: Exchange: MessageSocket]: Describe[F] =
    new Describe[F] {

      // promote a command to a query ... weird case, see below
      def promote[A](cmd: skunk.Command[A]): skunk.Query[A, skunk.Void] =
        skunk.Query(cmd.sql, cmd.origin, cmd.encoder, skunk.Void.codec)

      def apply(cmd: skunk.Command[_], id: StatementId, ty: Typer): F[Unit] =
        exchange {
          for {
            _  <- send(DescribeMessage.statement(id.value))
            _  <- send(Flush)
            _  <- expect { case ParameterDescription(_) => } // always ok
            _  <- flatExpect {
                    case NoData                 => ().pure[F]
                    case rd @ RowDescription(_) =>
                      rd.typed(ty) match {
                        // We shouldn't have a row description at all, but if we can't decode its
                        // types then *that's* the error we will raise; only if we decode it
                        // successfully will we raise the underlying error. Seems a little confusing
                        // but it's a very unlikely case so I think we're ok for the time being.
                        case Left(err) => UnknownOidException(promote(cmd), err).raiseError[F, Unit]
                        case Right(td) => UnexpectedRowsException(cmd, td).raiseError[F, Unit]
                      }
                  }
          } yield ()
        }

      def apply[A](query: skunk.Query[_, A], id: StatementId, ty: Typer): F[TypedRowDescription] =
        exchange {
          for {
            _  <- send(DescribeMessage.statement(id.value))
            _  <- send(Flush)
            _  <- expect { case ParameterDescription(_) => } // always ok
            rd <- flatExpect {
                    case rd @ RowDescription(_) => rd.pure[F]
                    case NoData                 => NoDataException(query).raiseError[F, RowDescription]
                  }
            td <- rd.typed(ty) match {
                    case Left(err) => UnknownOidException(query, err).raiseError[F, TypedRowDescription]
                    case Right(td) => td.pure[F]
                  }
            _  <- ColumnAlignmentException(query, td).raiseError[F, Unit].unlessA(query.decoder.types === td.types)
          } yield td
        }

    }

}