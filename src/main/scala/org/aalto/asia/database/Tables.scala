package org.aalto.asia.database

import akka.event.{ LoggingAdapter }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

//import slick.driver.H2Driver.api._

import slick.basic.DatabaseConfig
//import slick.driver.h2driver.api._
import slick.jdbc.JdbcProfile
import org.aalto.asia.types.Path
import org.aalto.asia.requests._

case class PermissionEntry(
  val groupId: Long,
  val request: String,
  val allow: Boolean,
  val path: Path)

case class UserEntry(
  val id: Option[Long],
  val name: String) {
  val groupName = s"${name}_USERGROUP"
}

case class GroupEntry(
  val id: Option[Long],
  val name: String)

case class MemberEntry(
  val groupId: Long,
  val userId: Long)

case class SubGroupEntry(
  val groupId: Long,
  val subGroupId: Long)

trait DBBase {
  val dc: DatabaseConfig[JdbcProfile] //= DatabaseConfig.forConfig[JdbcProfile](database.dbConfigName)
  import dc.profile.api._
  val db: Database
  //protected[this] val db: Database
}

trait AuthorizationTables extends DBBase {
  import dc.profile.api._
  import dc.profile.api.DBIOAction

  def log: LoggingAdapter
  type DBSIOro[Result] = DBIOAction[Seq[Result], Streaming[Result], Effect.Read]
  type DBIOro[Result] = DBIOAction[Result, NoStream, Effect.Read]
  type DBIOwo[Result] = DBIOAction[Result, NoStream, Effect.Write]
  type DBIOsw[Result] = DBIOAction[Result, NoStream, Effect.Schema with Effect.Write]
  type ReadWrite = Effect with Effect.Write with Effect.Read with Effect.Transactional
  type DBIOrw[Result] = DBIOAction[Result, NoStream, ReadWrite]

  implicit lazy val pathColumnType = MappedColumnType.base[Path, String](
    { p: Path => p.toString },
    { str: String => Path(str) } // String to Path
  )

  class UsersTable(tag: Tag) extends Table[UserEntry](tag, "USERS") {
    def userId: Rep[Long] = column[Long]("USER_ID", O.PrimaryKey, O.AutoInc)
    def name: Rep[String] = column[String]("NAME", O.Unique)

    def nameIndex = index("USERNAME_INDEX", name, unique = true)

    def * = (userId?, name) <> (UserEntry.tupled, UserEntry.unapply)
  }

  class Users extends TableQuery[UsersTable](new UsersTable(_))
  val usersTable = new Users()

  class GroupsTable(tag: Tag) extends Table[GroupEntry](tag, "GROUPS") {
    def groupId: Rep[Long] = column[Long]("GROUP_ID", O.PrimaryKey, O.AutoInc)
    def name: Rep[String] = column[String]("NAME", O.Unique)

    def nameIndex = index("GROUPNAME_INDEX", name, unique = true)

    def * = (groupId?, name) <> (GroupEntry.tupled, GroupEntry.unapply)
  }

  class Groups extends TableQuery[GroupsTable](new GroupsTable(_))
  val groupsTable = new Groups()

  class MembersTable(tag: Tag) extends Table[MemberEntry](tag, "MEMBERS") {
    def groupId: Rep[Long] = column[Long]("GROUP_ID")
    def userId: Rep[Long] = column[Long]("USER_ID")
    def userIndex = index("MEMBERS_USER_INDEX", userId, unique = false)
    def groupIndex = index("MEMBERS_GROUP_INDEX", groupId, unique = false)
    def groupsFK = foreignKey("MEMBERS_GROUP_FK", groupId, groupsTable)(_.groupId, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
    def usersFK = foreignKey("MEMBERS_USER_FK", userId, usersTable)(_.userId, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
    def * = (groupId, userId) <> (MemberEntry.tupled, MemberEntry.unapply)
  }
  class Members extends TableQuery[MembersTable](new MembersTable(_))
  val membersTable = new Members()

  /*
  class SubGroupsTable(tag: Tag) extends Table[SubGroupEntry](tag, "SUBGROUPS") {
    def groupId: Rep[Long] = column[Long]("GROUP_ID")
    def subGroupId: Rep[Long] = column[Long]("SUB_GROUP_ID")
    def subGroupIndex = index("SUBG_SUBGROUP_INDEX", subGroupId, unique = false)
    def groupIndex = index("SUBG_GROUP_INDEX", groupId, unique = false)
    def groupsFK = foreignKey("GROUP_FK", groupId, groupsTable)(_.groupId, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
    def subgroupsFK = foreignKey("SUBGROUP_FK", subGroupId, groupsTable)(_.groupId, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
    def * = (groupId, subGroupId) <> (SubGroupEntry.tupled, SubGroupEntry.unapply)
  }
  class SubGroups extends TableQuery[SubGroupsTable](new SubGroupsTable(_))
  val subGroupsTable = new SubGroups()
  */

  class PermissionsTable(tag: Tag) extends Table[PermissionEntry](tag, "RULES") {
    def groupId: Rep[Long] = column[Long]("GROUP_ID")
    def request: Rep[String] = column[String]("REQUEST")
    def path: Rep[Path] = column[Path]("PATH")
    def allow: Rep[Boolean] = column[Boolean]("ALLOW_OR_DENY")

    def groupIndex = index("RULES_GROUP_INDEX", groupId, unique = false)
    def groupRequestIndex = index("RULES_GROUP_REQUEST_INDEX", (groupId, request), unique = false)

    def groupsFK = foreignKey("RULES_GROUP_FK", groupId, groupsTable)(_.groupId, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
    def * = (groupId, request, allow, path) <> (PermissionEntry.tupled, PermissionEntry.unapply)
  }

  class Permissions extends TableQuery[PermissionsTable](new PermissionsTable(_))
  val permissionsTable = new Permissions()

  protected def getPermissionsIOA(username: String, groups: Set[String], request: Request) = {
    val user = usersTable.filter { row => row.name === username }
    val knownGroups = groupsTable.filter { row => row.name.inSet(groups ++ Set("DEFAULT")) }.map { row => row.groupId }
    val groupsInDB = for {
      (user, member) <- user join membersTable on { (user, memberEntry) => memberEntry.userId === user.userId }
    } yield (member.groupId)
    /*
    //Recursive subgroup getter
    def tmp(groupIds: Set[Long]): DBIOro[Set[Long]] = {
      subGroupsTable.filter {
        row => row.subGroupId inSet (groupIds)
      }.map(_.groupId).result.map {
        parentGroupIds: Seq[Long] =>
          groupIds ++ parentGroupIds.toSet
      }.flatMap {
        gIds: Set[Long] =>
          if (gIds.size > groupIds.size) {
            tmp(gIds)
          } else {
            DBIO.successful(gIds)
          }
      }
    }*/
    val allGroups: DBIOro[Set[Long]] = groupsInDB.result.flatMap {
      result =>
        knownGroups.result.map {
          kgResult =>
            result.toSet ++ kgResult.toSet
        }
    } /*.flatMap {
      groupIds: Seq[Long] =>
        tmp(groupIds.toSet)
    }*/
    val action = allGroups.flatMap {
      groupIds: Set[Long] =>
        log.info(s"Found following group ids for $username: $groupIds")
        permissionsTable.filter {
          row =>
            (row.groupId inSet (groupIds)) &&
              (row.request like (s"%${request.toString}%"))
        }.result.map {
          permissions: Seq[PermissionEntry] =>
            log.info(s"Got following permissions for $username: $permissions")
            val (allows, denies) = permissions.partition(_.allow)
            val deniedPaths: Set[Path] = denies.groupBy(_.groupId).mapValues {
              permissions: Seq[PermissionEntry] =>
                permissions.map(_.path).toSet
            }.values.reduceOption[Set[Path]] {
              case (result: Set[Path], r: Set[Path]) =>
                r.filter {
                  path =>
                    result.contains(path) ||
                      result.exists {
                        op => op.isAncestorOf(path)
                      }
                } ++ result.filter {
                  path =>
                    r.contains(path) ||
                      r.exists {
                        op => op.isAncestorOf(path)
                      }
                }
            }.getOrElse(Set.empty[Path])
            val allowedPaths: Set[Path] = allows.groupBy(_.groupId).mapValues {
              permissions: Seq[PermissionEntry] =>
                permissions.map(_.path).toSet
            }.values.reduceOption[Set[Path]] { _ ++ _ }
              .getOrElse(Set.empty[Path])
            PermissionResult(allowedPaths, deniedPaths)
        }

    }
    action
  }

  protected def joinGroupsAction(username: String, groups: Set[String]) = {
    val crossJoin = for {
      user <- usersTable.filter { row => row.name === username }
      group <- groupsTable.filter { row => row.name inSet (groups) }
    } yield (group.groupId, user.userId)
    val action = crossJoin.result.flatMap {
      tuples =>
        log.info(tuples.mkString("\n"))
        val entries = tuples.map {
          case (gid, uid) => MemberEntry(gid, uid)
        }
        membersTable ++= entries
    }
    action
  }
}
